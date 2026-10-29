(ns aurashots.features.pages.core
  "Slice `pages`, pure logic (architecture.md section 2.3; api-contract.md section 6): generates the SEO region
  (title, description, canonical, Open Graph, Twitter, JSON-LD) from live data using the patterns of design-spec
  Part 6, rewrites the `<!--@seo:begin-->`..`<!--@seo:end-->` region and the `<!--@data-->` marker of HTML files,
  and builds `sitemap.xml` and `robots.txt`. No I/O: file contents and data are passed in."
  (:require [aurashots.platform.http-core.json :as json]
            [clojure.string :as str])
  (:import (java.util.regex Matcher)))

(defn esc
  "Escapes `s` for use in HTML text and double-quoted attributes."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(def csp
  "Content-Security-Policy for all HTML: same-origin only, no inline script or style (api-contract.md section 6)."
  (str "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; "
       "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))

(def indexable-static-paths
  "Static, indexable public paths listed in the sitemap (design-spec Part 6)."
  ["/" "/services" "/portfolio" "/shop" "/about" "/contact"
   "/legal/terms-of-sale" "/legal/refund-policy" "/legal/privacy" "/legal/order-terms"])

(def legal-titles
  "Page titles of the legal documents (design-spec Part 6, P15-P18), by document slug."
  {"terms-of-sale" "Terms of Sale | Aura Shots"
   "refund-policy" "Refund Policy | Aura Shots"
   "privacy" "Privacy Policy | Aura Shots"
   "order-terms" "Order Terms | Aura Shots"})

(def default-og-image-path
  "Served path of the 1200x630 Open Graph image used by pages without their own photo."
  "/assets/og-image.png")

(defn- absolute
  "Joins `base` and a path or nil."
  [base path]
  (when path (str base path)))

(defn- truncate
  "Shortens `s` to at most `n` characters for meta descriptions."
  [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (str/trim (subs s 0 (- n 1))) "…") s)))

(def description-floor
  "Fewest characters a generated meta description built from stored text may have; shorter source text is replaced
  by a composed description."
  50)

(defn- clean
  "Collapses whitespace in `s` and trims it."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

(defn- first-long-enough
  "The first of `candidates` (strings) that has at least `description-floor` characters after cleaning, or nil."
  [candidates]
  (first (filter #(>= (count %) description-floor) (map clean candidates))))

(defn- summary-phrase
  "A short phrase for composed descriptions: `s` without its final full stop when it has at least 10 characters,
  otherwise `default`."
  [s default]
  (let [s (str/replace (clean s) #"\.+$" "")]
    (if (>= (count s) 10) s default)))

(defn service-description
  "Meta description of a service: the short description or the long description when one has at least 50
  characters, otherwise `{Name} – {summary}. Request it online with Aura Shots, Kigali.`"
  [{:keys [name shortDescription description]}]
  (or (first-long-enough [shortDescription description])
      (str (clean name) " – " (summary-phrase (or (not-empty (clean shortDescription)) description) "private photo session in Kigali")
           ". Request it online with Aura Shots, Kigali.")))

(defn product-description
  "Meta description of a product: its description when it has at least 50 characters, otherwise
  `{Name} – {summary}. Order online from Aura Shots, Kigali.`"
  [{:keys [name description]}]
  (or (first-long-enough [description])
      (str (clean name) " – " (summary-phrase description "Aura Shots merchandise") ". Order online from Aura Shots, Kigali.")))

(defn legal-description
  "Meta description of a legal document: its first key point when it has at least 50 characters, otherwise
  `{Title} of Aura Shots, Kigali: read the full document before you book a session or order merchandise.`"
  [{:keys [title summaryPoints]}]
  (or (first-long-enough (take 1 summaryPoints))
      (str (clean title) " of Aura Shots, Kigali: read the full document before you book a session or order merchandise.")))

(defn- image-meta
  "Open Graph image details for a photo `image` (an `Image` map) of an item called `item-name`: dimensions from the
  stored derivative when both are positive, and the image alt text or else the item name. Returns nil when the item
  has no photo url."
  [image item-name]
  (when (:url image)
    (cond-> {:image-alt (or (not-empty (clean (:alt image))) item-name)}
      (and (pos? (or (:width image) 0)) (pos? (or (:height image) 0))) (assoc :image-size [(:width image) (:height image)]))))

(def ^:private day-names
  {"mon" "Monday" "tue" "Tuesday" "wed" "Wednesday" "thu" "Thursday" "fri" "Friday" "sat" "Saturday" "sun" "Sunday"})

(defn professional-service
  "The schema.org `ProfessionalService` node for the business from `info` (the public site-info map)."
  [base info image]
  (cond-> {"@type" "ProfessionalService" "name" "Aura Shots" "url" base
           "description" "Private, appointment-only editorial photography in Kigali."
           "priceRange" "RWF"
           "address" {"@type" "PostalAddress" "streetAddress" (:address info) "addressCountry" "RW"}
           "openingHoursSpecification" (mapv (fn [{:keys [days open close]}]
                                               {"@type" "OpeningHoursSpecification"
                                                "dayOfWeek" (mapv day-names days) "opens" open "closes" close})
                                             (:openingHours info))}
    image (assoc "image" image)
    (:phone info) (assoc "telephone" (:phone info))
    (:email info) (assoc "email" (:email info))
    (seq (remove nil? (vals (:social info)))) (assoc "sameAs" (vec (remove nil? (vals (:social info)))))))

(defn breadcrumbs
  "A schema.org `BreadcrumbList` node for `crumbs`, a vector of `[name absolute-url]`."
  [crumbs]
  {"@type" "BreadcrumbList"
   "itemListElement" (vec (map-indexed (fn [i [n u]] {"@type" "ListItem" "position" (inc i) "name" n "item" u}) crumbs))})

(defn home-seo
  "SEO data for `/` from the site `info` map and optional `image` path (nil: the shared Open Graph image is added
  by `with-default-image`; the JSON-LD always uses it)."
  [base info image]
  {:title "Aura Shots | Editorial Photography Sessions in Kigali"
   :description "Private, appointment-only editorial photography in Kigali: portraits, couple and birthday sessions with cinematic style. Request your session online."
   
   :canonical (str base "/") :image (absolute base image) :og-type "website"
   :json-ld {"@context" "https://schema.org" "@graph" [(professional-service base info (absolute base default-og-image-path))]}})

(defn contact-seo
  "SEO data for `/contact`."
  [base info image]
  {:title "Contact Aura Shots | Kigali Photographer"
   :description (truncate (str "Contact Aura Shots in " (:address info) ". Message us on WhatsApp, call, or send an enquiry about your photo session.") 160)
   :canonical (str base "/contact") :image (absolute base image) :og-type "website"
   :json-ld {"@context" "https://schema.org" "@graph" [(professional-service base info (absolute base default-og-image-path))]}})

(defn legal-seo
  "SEO data for a legal document page given its document body `doc` (public document map) and its `slug`."
  [base slug doc image]
  {:title (get legal-titles slug (str (:title doc) " | Aura Shots"))
   :description (truncate (legal-description doc) 160)
   :canonical (str base "/legal/" slug) :image (absolute base image) :og-type "website" :json-ld nil})

(defn service-seo
  "SEO data for a service detail page from the public service `detail` map (see storefront.api/service-detail)."
  [base detail info]
  (let [url (str base "/services/" (:slug detail))
        image (some->> (:mainImage detail) :url (absolute base))
        description (service-description detail)]
    (merge
     {:title (str (:name detail) " – Photoshoot in Kigali | Aura Shots")
      :description (truncate description 160)
      :canonical url :image image :og-type "website"
      :json-ld {"@context" "https://schema.org"
                "@graph" [(cond-> {"@type" "Service" "name" (:name detail) "url" url
                                   "description" description
                                   "areaServed" "Kigali, Rwanda"
                                   "provider" {"@type" "ProfessionalService" "name" "Aura Shots" "url" base
                                               "address" {"@type" "PostalAddress" "streetAddress" (:address info) "addressCountry" "RW"}}
                                   "offers" {"@type" "Offer" "price" (:priceRwf detail) "priceCurrency" "RWF"
                                             "availability" "https://schema.org/InStock" "url" url}}
                            image (assoc "image" image))
                          (breadcrumbs [["Home" (str base "/")] ["Services" (str base "/services")] [(:name detail) url]])]}}
     (when image (image-meta (:mainImage detail) (:name detail))))))

(defn product-seo
  "SEO data for a product detail page from the public product `detail` map."
  [base detail]
  (let [url (str base "/shop/" (:slug detail))
        image (some->> (:mainImage detail) :url (absolute base))
        description (product-description detail)]
    (merge
     {:title (str (:name detail) " | Aura Shots Merchandise")
      :description (truncate description 160)
      :canonical url :image image :og-type "product"
      :json-ld {"@context" "https://schema.org"
                "@graph" [(cond-> {"@type" "Product" "name" (:name detail) "url" url
                                   "description" description
                                   "brand" {"@type" "Brand" "name" "Aura Shots"}
                                   "offers" {"@type" "Offer" "price" (:priceRwf detail) "priceCurrency" "RWF" "url" url
                                             "availability" (if (= "inStock" (:availability detail))
                                                              "https://schema.org/InStock" "https://schema.org/OutOfStock")}}
                            image (assoc "image" image))
                          (breadcrumbs [["Home" (str base "/")] ["Shop" (str base "/shop")] [(:name detail) url]])]}}
     (when image (image-meta (:mainImage detail) (:name detail))))))

(def default-og-image-alt
  "Alt text of the default Open Graph image."
  "Aura Shots, editorial photography sessions in Kigali")

(defn with-default-image
  "Returns `seo` with the shared 1200x630 Open Graph image (absolute under `base`) when it has no image of its own;
  the JSON-LD is left as it is."
  [seo base]
  (if (:image seo)
    seo
    (assoc seo :image (str base default-og-image-path) :image-size [1200 630] :image-alt default-og-image-alt)))

(defn seo-region
  "Renders the complete SEO region (markers included) for `seo` `{:title :description :canonical :image :og-type
  :json-ld}`."
  [{:keys [title description canonical image og-type json-ld image-size image-alt]}]
  (str "<!--@seo:begin-->\n"
       "<title>" (esc title) "</title>\n"
       "<meta name=\"description\" content=\"" (esc description) "\">\n"
       "<link rel=\"canonical\" href=\"" (esc canonical) "\">\n"
       "<meta property=\"og:site_name\" content=\"Aura Shots\">\n"
       "<meta property=\"og:type\" content=\"" (esc (or og-type "website")) "\">\n"
       "<meta property=\"og:title\" content=\"" (esc title) "\">\n"
       "<meta property=\"og:description\" content=\"" (esc description) "\">\n"
       "<meta property=\"og:url\" content=\"" (esc canonical) "\">\n"
       (when image (str "<meta property=\"og:image\" content=\"" (esc image) "\">\n"))
       (when image-size (str "<meta property=\"og:image:width\" content=\"" (first image-size) "\">\n"
                             "<meta property=\"og:image:height\" content=\"" (second image-size) "\">\n"))
       (when image-alt (str "<meta property=\"og:image:alt\" content=\"" (esc image-alt) "\">\n"))
       "<meta name=\"twitter:card\" content=\"summary_large_image\">\n"
       "<meta name=\"twitter:title\" content=\"" (esc title) "\">\n"
       "<meta name=\"twitter:description\" content=\"" (esc description) "\">\n"
       (when image (str "<meta name=\"twitter:image\" content=\"" (esc image) "\">\n"))
       (when image-alt (str "<meta name=\"twitter:image:alt\" content=\"" (esc image-alt) "\">\n"))
       (when json-ld (str "<script type=\"application/ld+json\">" (json/script-safe json-ld) "</script>\n"))
       "<!--@seo:end-->"))

(defn rewrite-seo
  "Replaces the SEO region in `html` with `region` (from `seo-region`). Returns `html` unchanged when it has no region."
  [html region]
  (str/replace html #"(?s)<!--@seo:begin-->.*?<!--@seo:end-->" (Matcher/quoteReplacement region)))

(defn rewrite-data
  "Replaces the single `<!--@data-->` marker with the initial-data script holding `data` (script-safe JSON)."
  [html data]
  (str/replace html "<!--@data-->"
               (Matcher/quoteReplacement
                (str "<script type=\"application/json\" id=\"initial-data\">" (json/script-safe data) "</script>"))))

(defn sitemap-xml
  "Builds `sitemap.xml` for absolute URL strings `urls`."
  [urls]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n"
       (str/join (map #(str "  <url><loc>" (esc %) "</loc></url>\n") urls))
       "</urlset>\n"))

(def robots-disallow-all
  "`robots.txt` served outside production: no crawler may index anything."
  "User-agent: *\nDisallow: /")

(defn robots-txt
  "Builds the production `robots.txt`: disallows admin, API and the noindex pages and points at the sitemap."
  [base]
  (str "User-agent: *\n"
       "Disallow: /admin\nDisallow: /api/\nDisallow: /book\nDisallow: /cart/\nDisallow: /shop/checkout\nDisallow: /shop/received/\n"
       "Sitemap: " base "/sitemap.xml\n"))

(def content-types
  "Content types of static files by lower-case extension."
  {"html" "text/html; charset=utf-8" "css" "text/css; charset=utf-8" "js" "text/javascript; charset=utf-8"
   "mjs" "text/javascript; charset=utf-8" "json" "application/json; charset=utf-8" "svg" "image/svg+xml"
   "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "webp" "image/webp" "gif" "image/gif" "ico" "image/x-icon"
   "woff" "font/woff" "woff2" "font/woff2" "ttf" "font/ttf" "otf" "font/otf" "txt" "text/plain; charset=utf-8"
   "webmanifest" "application/manifest+json" "map" "application/json"})

(defn content-type-of
  "Content type for file name `name` (octet-stream when the extension is unknown)."
  [name]
  (get content-types (some-> (re-find #"\.([A-Za-z0-9]+)$" name) second str/lower-case) "application/octet-stream"))
