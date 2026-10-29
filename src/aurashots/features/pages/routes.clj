(ns aurashots.features.pages.routes
  "Slice `pages`, HTTP routes (api-contract.md section 6, 2.16 and 2.17): serves the HTML files built by the
  frontend (the web root), rewriting the SEO region and the initial-data marker on the dynamic-head pages,
  real 404 pages, static assets with long cache lifetimes, `sitemap.xml`, `robots.txt` and the health check.
  The route table below is the one in the contract; the frontend creates exactly these files."
  (:require [aurashots.features.catalog.api :as catalog]
            [aurashots.features.documents.api :as documents]
            [aurashots.features.media.api :as media]
            [aurashots.features.merch.api :as merch]
            [aurashots.features.pages.core :as core]
            [aurashots.features.site.api :as site]
            [aurashots.features.storefront.api :as storefront]
            [aurashots.platform.http-core.response :as resp]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import (java.io File)))

(defn- read-page
  "Reads `<web-root>/<rel>` as a string, or nil when it does not exist."
  [web-root rel]
  (let [f (io/file web-root rel)]
    (when (.isFile f) (slurp f))))

(defn- html-response
  "Builds an HTML response. `kind` is :dynamic (60 s cache plus ETag), :static (5 min), :noindex (5 min,
  `X-Robots-Tag: noindex`) or :admin (no-store, noindex)."
  [status body kind]
  (cond-> {:status status
           :headers (cond-> {"Content-Type" "text/html; charset=utf-8" "Content-Security-Policy" core/csp}
                      (= kind :dynamic) (assoc "Cache-Control" resp/public-cache)
                      (#{:static :noindex} kind) (assoc "Cache-Control" "public, max-age=300")
                      (#{:noindex :admin :not-found} kind) (assoc "X-Robots-Tag" "noindex")
                      (#{:admin :not-found} kind) (assoc "Cache-Control" "no-store"))
           :body body}
    (= kind :dynamic) (assoc :etag? true)))

(defn not-found
  "The 404 response: `site/404.html` from the web root (or a minimal built-in page when it is missing)."
  [{:keys [config]}]
  (html-response 404
                 (or (read-page (:web-root config) "site/404.html")
                     "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Page not found | Aura Shots</title></head><body><h1>Page not found</h1></body></html>")
                 :not-found))

(defn- missing-file
  "Response when a page file is absent from the web root (the frontend has not been built)."
  [file]
  (log/warn "Page file missing in web root" {:file file})
  (html-response 404 "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Not available</title></head><body><h1>This page is not available yet</h1></body></html>" :not-found))

(defn- static-page
  "Handler serving a static HTML file `file` (relative to the web root) with cache kind `kind`."
  [{:keys [config]} file kind]
  (fn [_]
    (if-let [html (read-page (:web-root config) file)]
      (html-response 200 html kind)
      (missing-file file))))

(defn- seo-image
  "Absolute path of the best Open Graph image: the item's own image unless it is the placeholder, else the
  featured portfolio image, else nil."
  [{:keys [conn featured-image]} item-image]
  (let [img (if (and item-image (not= media/placeholder-id (:id item-image))) item-image (featured-image conn))]
    (:url img)))

(defn- dynamic-page
  "Handler serving `file` with its SEO region rewritten by `seo-fn`, a function of the request returning the SEO map
  (or nil for a real 404). `data-fn` (optional) returns the initial-data map for the `<!--@data-->` marker."
  [{:keys [config] :as deps} file seo-fn & [data-fn]]
  (fn [req]
    (if-let [seo (seo-fn req)]
      (if-let [html (read-page (:web-root config) file)]
        (html-response 200
                       (cond-> (core/rewrite-seo html (core/seo-region seo))
                         data-fn (core/rewrite-data (data-fn req)))
                       :dynamic)
        (missing-file file))
      (not-found deps))))

(defn- serve-static
  "Handler serving files below `<web-root>/<dir>` for the wildcard path param `:path`, with one-year immutable
  caching. The resolved path must stay inside the directory; anything else is the 404 page."
  [{:keys [config] :as deps} dir]
  (fn [req]
    (let [root (.getCanonicalFile (io/file (:web-root config) dir))
          rel (get-in req [:path-params :path])
          f (when (and rel (not (str/includes? rel "\u0000"))) (.getCanonicalFile (io/file root rel)))]
      (if (and f (.startsWith (.getPath ^File f) (str (.getPath root) File/separator)) (.isFile ^File f))
        {:status 200 :body f
         :headers {"Content-Type" (core/content-type-of (.getName ^File f))
                   "Cache-Control" "public, max-age=31536000, immutable"}}
        (not-found deps)))))

(defn- sitemap
  "Builds the sitemap response from published services and products plus the indexable static pages."
  [{:keys [conn config]}]
  (let [base (:public-base-url config)
        urls (concat (map #(str base %) core/indexable-static-paths)
                     (map #(str base "/services/" %) (catalog/published-slugs conn))
                     (map #(str base "/shop/" %) (merch/published-slugs conn)))]
    {:status 200 :headers {"Content-Type" "application/xml; charset=utf-8" "Cache-Control" resp/public-cache}
     :body (core/sitemap-xml urls)}))

(defn routes
  "Returns the route specs of the pages slice. `deps` keys: `:conn`, `:config`, `:featured-image` (function
  `(conn) -> Image|nil` injected from the portfolio slice by `system`)."
  [{:keys [conn config] :as deps}]
  (let [base (:public-base-url config)
        owner (:owner-email config)
        info (fn [] (site/public-info conn owner))
        public (fn [name path handler] {:name name :method :get :path path :public true :no-input true :handler handler})
        page (fn [name path file kind] (public name path (static-page deps file kind)))
        legal (fn [slug]
                (public (keyword "pages" (str "legal-" slug)) (str "/legal/" slug)
                        (dynamic-page deps "site/legal.html"
                                      (fn [_] (when-let [doc (documents/public-document conn slug nil)]
                                                (core/legal-seo base slug doc (seo-image deps nil)))))))
        asset (fn [name dir] (public name (str "/" dir "/*path") (serve-static deps dir)))]
    [(public :pages/home "/" (dynamic-page deps "site/index.html" (fn [_] (core/home-seo base (info) (seo-image deps nil)))))
     (page :pages/services "/services" "site/services.html" :static)
     (public :pages/service "/services/:slug"
             (dynamic-page deps "site/service.html"
                           (fn [req] (when-let [d (storefront/service-detail conn (get-in req [:path-params :slug]))]
                                       (core/service-seo base (assoc-in d [:mainImage :url] (or (seo-image deps (:mainImage d)) (get-in d [:mainImage :url]))) (info))))
                           (fn [req] (storefront/service-detail conn (get-in req [:path-params :slug])))))
     (page :pages/portfolio "/portfolio" "site/portfolio.html" :static)
     (page :pages/shop "/shop" "site/shop.html" :static)
     (page :pages/checkout "/shop/checkout" "site/checkout.html" :noindex)
     (page :pages/order-received "/shop/received/:reference" "site/order-received.html" :noindex)
     (public :pages/product "/shop/:slug"
             (dynamic-page deps "site/product.html"
                           (fn [req] (when-let [d (storefront/product-detail conn (get-in req [:path-params :slug]))]
                                       (core/product-seo base (assoc-in d [:mainImage :url] (or (seo-image deps (:mainImage d)) (get-in d [:mainImage :url]))))))
                           (fn [req] (storefront/product-detail conn (get-in req [:path-params :slug])))))
     (page :pages/cart-sessions "/cart/sessions" "site/cart-sessions.html" :noindex)
     (page :pages/cart-merchandise "/cart/merchandise" "site/cart-merchandise.html" :noindex)
     (page :pages/book "/book" "site/book.html" :noindex)
     (page :pages/book-received "/book/received/:reference" "site/book-received.html" :noindex)
     (page :pages/about "/about" "site/about.html" :static)
     (public :pages/contact "/contact"
             (dynamic-page deps "site/contact.html" (fn [_] (core/contact-seo base (info) (seo-image deps nil)))))
     (legal "terms-of-sale") (legal "refund-policy") (legal "privacy") (legal "order-terms")
     (page :pages/admin "/admin" "admin/index.html" :admin)
     (public :pages/admin-any "/admin/*path" (static-page deps "admin/index.html" :admin))
     (asset :pages/assets "assets") (asset :pages/css "css") (asset :pages/js "js") (asset :pages/fonts "fonts")
     (public :pages/sitemap "/sitemap.xml" (fn [_] (sitemap deps)))
     (public :pages/robots "/robots.txt"
             (fn [_] {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" resp/public-cache}
                      :body (core/robots-txt base)}))
     (public :pages/health "/api/health" (fn [_] (resp/ok {:status "ok"})))]))
