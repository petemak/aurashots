(ns aurashots.integration.pages-test
  "Integration tests of page serving (api-contract.md section 6): SEO region rewriting from live data, the
  initial-data block, real 404s, noindex and cache headers, CSP, static assets, sitemap and robots."
  (:require [aurashots.test-helpers :as h :refer [call]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def env "The running test system." (atom nil))

(use-fixtures :once
  (fn [f]
    (reset! env (h/start-system {:web-root (h/web-root-fixture)}))
    (try (f) (finally (h/stop-system @env)))))

(deftest ^{:doc "Tests home has live seo region."} home-has-live-seo-region
  (let [r (call @env :get "/")
        body (:body r)]
    (is (= 200 (:status r)))
    (is (str/includes? body "<title>Aura Shots | Editorial Photography Sessions in Kigali</title>"))
    (is (not (str/includes? body "<title>fallback</title>")))
    (is (str/includes? body "<h1>Home</h1>") "body is untouched")
    (is (= 1 (count (re-seq #"<title>" body))))
    (is (str/includes? body "application/ld+json"))
    (is (str/includes? body "ProfessionalService"))
    (is (str/includes? body "og:image"))
    (is (= "public, max-age=60, stale-while-revalidate=300" (get-in r [:headers "Cache-Control"])))
    (is (some? (get-in r [:headers "ETag"])))
    (is (str/includes? (get-in r [:headers "Content-Security-Policy"]) "default-src 'self'"))
    (testing "conditional request"
      (is (= 304 (:status (call @env :get "/" {:headers {"if-none-match" (get-in r [:headers "ETag"])}})))))))

(deftest ^{:doc "Tests service page has title jsonld and initial data."} service-page-has-title-jsonld-and-initial-data
  (let [r (call @env :get "/services/single-session")
        body (:body r)
        api (:body (call @env :get "/api/services/single-session"))]
    (is (= 200 (:status r)))
    (is (str/includes? body "<title>Single Session – Photoshoot in Kigali | Aura Shots</title>"))
    (is (str/includes? body "\"priceCurrency\":\"RWF\""))
    (is (str/includes? body "\"price\":50000"))
    (is (str/includes? body "BreadcrumbList"))
    (is (str/includes? body "<link rel=\"canonical\" href=\"http://localhost:8080/services/single-session\">"))
    (testing "initial-data equals the API response"
      (let [m (re-find #"(?s)<script type=\"application/json\" id=\"initial-data\">(.*?)</script>" body)
            parsed (h/parse-body (second m))]
        (is (some? m))
        (is (= api parsed))))
    (testing "a live edit shows in the next response"
      (let [owner (h/owner-session @env)
            sv (get-in (call @env :get "/api/admin/services/seed-service-single-session" {:session owner}) [:body])
            put (call @env :put "/api/admin/services/seed-service-single-session"
                      {:session owner :body (-> sv (select-keys [:name :shortDescription :description :priceRwf :sessionMinutes :contents :conditions :relatedProductIds :featured :sortOrder])
                                                (assoc :name "Solo Session" :status "published"
                                                       :images (mapv #(select-keys % [:imageId :alt]) (:images sv))))})]
        (is (= 200 (:status put)))
        (is (str/includes? (:body (call @env :get "/services/single-session")) "Solo Session – Photoshoot in Kigali"))))))

(deftest ^{:doc "Tests unknown and hidden are real 404s."} unknown-and-hidden-are-real-404s
  (doseq [p ["/services/nope" "/shop/nope" "/nope" "/legal/unknown"]]
    (let [r (call @env :get p)]
      (is (= 404 (:status r)) p)
      (is (str/includes? (:body r) "Not found"))
      (is (= "noindex" (get-in r [:headers "X-Robots-Tag"]))))))

(deftest ^{:doc "Tests noindex static and admin pages."} noindex-static-and-admin-pages
  (doseq [p ["/book" "/admin" "/admin/session-requests/abc" "/admin/help/staff"]]
    (let [r (call @env :get p)]
      (is (= 200 (:status r)) p)
      (is (= "noindex" (get-in r [:headers "X-Robots-Tag"])) p)))
  (is (= "no-store" (get-in (call @env :get "/admin") [:headers "Cache-Control"])))
  (is (= "public, max-age=300" (get-in (call @env :get "/services") [:headers "Cache-Control"])))
  (is (nil? (get-in (call @env :get "/services") [:headers "X-Robots-Tag"]))))

(deftest ^{:doc "Tests static assets and traversal."} static-assets-and-traversal
  (let [r (call @env :get "/css/site.abc123.css")]
    (is (= 200 (:status r)))
    (is (= "public, max-age=31536000, immutable" (get-in r [:headers "Cache-Control"])))
    (is (= "text/css; charset=utf-8" (get-in r [:headers "Content-Type"]))))
  (is (= 404 (:status (call @env :get "/css/..%2f..%2fsite%2findex.html"))))
  (is (= 404 (:status (call @env :get "/css/missing.css")))))

(deftest ^{:doc "Tests sitemap and robots."} sitemap-and-robots
  (let [s (:body (call @env :get "/sitemap.xml"))
        r (:body (call @env :get "/robots.txt"))]
    (is (str/includes? s "/services/single-session"))
    (is (str/includes? s "/legal/privacy"))
    (is (not (str/includes? s "/book")))
    (is (not (str/includes? s "/admin")))
    (is (str/includes? r "Disallow: /admin"))
    (is (str/includes? r "Disallow: /api/"))
    (is (str/includes? r "Sitemap: http://localhost:8080/sitemap.xml"))))

(deftest ^{:doc "Tests legal and contact heads."} legal-and-contact-heads
  (is (str/includes? (:body (call @env :get "/legal/refund-policy")) "<title>Refund Policy | Aura Shots</title>"))
  (is (str/includes? (:body (call @env :get "/contact")) "<title>Contact Aura Shots | Kigali Photographer</title>"))
  (testing "HEAD works"
    (is (= 200 (:status (call @env :head "/"))))))
