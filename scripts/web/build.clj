(ns build
  "Frontend build (architecture.md section 4, frontend-html). Run as `bb scripts/web/build.clj`
  (the backend's bb.edn exposes it as the task `build-web`).

  Writes dist/web/ with this layout, which the backend serves as WEB_ROOT (api-contract.md section 6):
    site/*.html, admin/index.html           pages, partials included, placeholders filled
    css/*.<hash>.css                        design-tokens.css, fonts.css, web/css/*.css
    js/*.<hash>.js, js/site/*.<hash>.js     bundles (no bundler: plain concatenation)
    fonts/*.<hash>.woff2, assets/**         copied from assets/ with fingerprinted names
  Environment: PUBLIC_BASE_URL (default https://aura-shots.com) replaces %%BASE_URL%% in the HTML,
  so canonical and Open Graph URLs of static pages are absolute and correct for the deployment.

  Admin help: admin-help.md is converted here to HTML (no Markdown parser ships to the browser)
  and written as js/admin-help.js. admin-help.md itself is never modified."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root (-> (or (System/getProperty "babashka.file") "scripts/web/build.clj")
              fs/absolutize fs/parent fs/parent fs/parent str))
(defn p [& parts] (str (apply fs/path root parts)))
(def dist (p "dist" "web"))
(def base-url (or (System/getenv "PUBLIC_BASE_URL") "https://aura-shots.com"))

(defn sha10
  "First ten hex characters of the SHA-256 of the given bytes."
  [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (subs (apply str (map #(format "%02x" %) (.digest md bs))) 0 10)))

(defn hashed-name
  "Inserts the content hash before the extension: site.css -> site.3f9a1c0b2d.css."
  [name h]
  (let [i (str/last-index-of name ".")]
    (str (subs name 0 i) "." h (subs name i))))

(def manifest
  "Logical URL path -> fingerprinted URL path, filled while building."
  (atom {}))

(defn write-hashed!
  "Writes bytes to dist under the directory of `logical`, with a content hash in the name,
  and records the mapping. Returns the public path."
  [logical ^bytes bs]
  (let [dir (fs/parent logical) nm (fs/file-name logical)
        pub (str dir "/" (hashed-name nm (sha10 bs)))
        out (io/file dist (subs pub 1))]
    (io/make-parents out)
    (with-open [o (io/output-stream out)] (.write o bs))
    (swap! manifest assoc logical pub)
    pub))

(defn rewrite
  "Replaces every known logical asset path in text by its fingerprinted path (longest first)."
  [text]
  (reduce (fn [t [k v]] (str/replace t k v)) text
          (sort-by (comp - count key) @manifest)))

(defn slurp-bytes [f] (fs/read-all-bytes f))
(defn utf8 [^String s] (.getBytes s "UTF-8"))

;; ---------------------------------------------------------------- assets and fonts
(defn build-assets!
  "Copies assets/ (icons, logo, badges, placeholders, favicons) with fingerprints. Fonts go to /fonts."
  []
  (doseq [f (fs/glob (p "assets") "**" {:recursive true}) :when (fs/regular-file? f)]
    (let [rel (str (fs/relativize (p "assets") f))
          nm (fs/file-name f)]
      (cond
        (str/starts-with? rel "fonts/")
        (cond
          (str/ends-with? nm ".woff2")
          (write-hashed! (str "/fonts/" nm) (slurp-bytes f))
          (str/ends-with? nm ".txt")
          (fs/copy f (doto (io/file dist "fonts" nm) io/make-parents) {:replace-existing true}))
        (or (= rel "site.webmanifest")) nil ; written after images, it refers to them
        :else (write-hashed! (str "/assets/" rel) (slurp-bytes f)))))
  (let [mf (p "assets" "site.webmanifest")]
    (write-hashed! "/assets/site.webmanifest" (utf8 (rewrite (slurp mf))))))

;; ---------------------------------------------------------------- css
(defn build-css!
  "Fingerprints tokens, fonts.css (url() rewritten to hashed font paths) and web/css/*.css."
  []
  (let [fonts (-> (slurp (p "assets" "fonts" "fonts.css"))
                  (str/replace #"url\(\"([^\"]+\.woff2)\"\)"
                               (fn [[_ n]] (str "url(\"" (get @manifest (str "/fonts/" n)) "\")"))))]
    (write-hashed! "/css/fonts.css" (utf8 fonts)))
  (write-hashed! "/css/design-tokens.css" (slurp-bytes (p "design-tokens.css")))
  (doseq [f (fs/glob (p "web" "css") "*.css")]
    (write-hashed! (str "/css/" (fs/file-name f)) (utf8 (rewrite (slurp (str f)))))))

;; ---------------------------------------------------------------- admin help (markdown -> html)
(defn esc [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn inline
  "Escapes text, then converts **bold** and [text](help:id) links."
  [s]
  (-> (esc s)
      (str/replace #"\*\*(.+?)\*\*" "<strong>$1</strong>")
      (str/replace #"\[([^\]]+)\]\(help:([a-z0-9-]+)\)" "<a href=\"/admin/help/$2\" data-help=\"$2\">$1</a>")))

(defn blocks->html
  "Turns the body lines of one topic into HTML. Supports ### headings (rendered as h2 because the
  topic title is the page h1), ordered and unordered lists, > quotes and paragraphs."
  [lines]
  (loop [ls lines, out [], para [], list-items nil, list-type nil, quote nil]
    (let [flush-para (fn [o pa] (if (seq pa) (conj o (str "<p>" (inline (str/join " " pa)) "</p>")) o))
          flush-list (fn [o items t] (if items (conj o (str "<" t ">" (apply str (map #(str "<li>" (inline %) "</li>") items)) "</" t ">")) o))
          flush-quote (fn [o q] (if q (conj o (str "<blockquote>" (apply str (map #(str "<p>" (inline (str/join " " %)) "</p>") (remove empty? q))) "</blockquote>")) o))
          flush-all (fn [o] (-> o (flush-para para) (flush-list list-items list-type) (flush-quote quote)))]
      (if-let [l (first ls)]
        (let [l (str/trimr l)]
          (cond
            (str/blank? l) (recur (rest ls) (flush-all out) [] nil nil nil)
            (str/starts-with? l "### ")
            (recur (rest ls) (conj (flush-all out) (str "<h2>" (inline (subs l 4)) "</h2>")) [] nil nil nil)
            (re-matches #"^>\s?.*" l)
            (let [t (str/trim (subs l 1))
                  q (or quote [[]])
                  q (if (str/blank? t) (conj q []) (conj (pop q) (conj (peek q) t)))]
              (recur (rest ls) (-> out (flush-para para) (flush-list list-items list-type)) [] nil nil q))
            (re-matches #"^\d+\.\s+.*" l)
            (recur (rest ls) (-> out (flush-para para) (flush-quote quote)) []
                   (conj (if (= list-type "ol") list-items (if list-items nil [])) (str/replace l #"^\d+\.\s+" "")) "ol" nil)
            (str/starts-with? l "- ")
            (recur (rest ls) (-> out (flush-para para) (flush-quote quote)) []
                   (conj (if (= list-type "ul") list-items (if list-items nil [])) (subs l 2)) "ul" nil)
            :else (recur (rest ls) (-> out (flush-list list-items list-type) (flush-quote quote)) (conj para l) nil nil nil)))
        (apply str (flush-all out))))))

(defn parse-help
  "Parses admin-help.md into a vector of topic maps {id title roles pages group summary html}."
  []
  (let [lines (str/split-lines (slurp (p "admin-help.md")))
        sections (->> lines (partition-by #(str/starts-with? % "## "))
                      (drop-while #(not (str/starts-with? (first %) "## ")))
                      (partition 2)
                      (map (fn [[[h] body]] [h body])))]
    (vec
     (for [[head body] sections :when (str/starts-with? head "## ")
           :let [[id title roles pages group] (map str/trim (str/split (subs head 3) #"\|"))
                 body (drop-while str/blank? body)
                 summary (first body)
                 rest-lines (rest body)]]
       {:id id :title title
        :roles (mapv str/trim (str/split (str/replace roles #"^roles:\s*" "") #","))
        :pages (mapv str/trim (str/split (str/replace pages #"^pages:\s*" "") #","))
        :group (str/replace group #"^group:\s*" "")
        :summary summary
        :html (blocks->html rest-lines)}))))

;; ---------------------------------------------------------------- js
(defn cat-files
  "Concatenates the given files (each wrapped by its own IIFE already) in the order given."
  [files]
  (str/join "\n;\n" (map slurp files)))

(defn build-js!
  "Bundles: js/site-common.js (api + shared), js/admin-app.js, js/admin-help.js (generated),
  and one js/site/<page>.js per page script."
  []
  (let [js (p "web" "js")
        shared (sort-by (juxt #(if (str/ends-with? % "dom.js") 0 1) identity) ; dom.js first: later files capture AS.h at load
                        (map str (fs/glob (fs/path js "shared") "*.js")))
        common (cons (str (fs/path js "api.js")) shared)]
    ;; Badge file names are assembled at run time (AS.badge), so the browser gets a logical -> hashed map.
    (write-hashed! "/js/site-common.js"
                   (utf8 (str (rewrite (cat-files common))
                              "\n;(function(AS){AS.assetMap=" (json/generate-string (into {} (filter #(str/starts-with? (key %) "/assets/badges/") @manifest))) ";})(window.AS=window.AS||{});")))
    (write-hashed! "/js/admin-app.js"
                   (utf8 (rewrite (cat-files (sort (map str (fs/glob (fs/path js "admin") "*.js")))))))
    (let [topics (parse-help)
          src (str "(function(AS){AS.helpTopics=" (json/generate-string topics) ";})(window.AS=window.AS||{});")]
      (write-hashed! "/js/admin-help.js" (utf8 src)))
    (doseq [f (fs/glob (fs/path js "site") "*.js")]
      (write-hashed! (str "/js/site/" (fs/file-name f)) (utf8 (rewrite (slurp (str f))))))))

;; ---------------------------------------------------------------- html
(defn build-html!
  "Fills includes and base URL, rewrites css/js/asset references, writes pages under dist/web."
  []
  (let [partials (into {} (for [f (fs/glob (p "web" "site" "_partials") "*.html")]
                            [(str/replace (fs/file-name f) ".html" "") (slurp (str f))]))
        render (fn [src]
                 (-> src
                     (str/replace #"<!--@include:([a-z-]+)-->" (fn [[_ n]] (or (partials n) (throw (ex-info (str "missing partial " n) {})))))
                     (str/replace "%%BASE_URL%%" base-url)
                     rewrite))
        pages (concat (map #(vector "site" %) (fs/glob (p "web" "site") "*.html"))
                      [["admin" (p "web" "admin" "index.html")]])]
    (doseq [[dir f] pages]
      (let [out (io/file dist dir (fs/file-name f))]
        (io/make-parents out)
        (spit out (render (slurp (str f))))))))

(defn check!
  "Fails the build if a built page contains an inline script, handler or style (strict CSP)."
  []
  (doseq [f (fs/glob dist "**/*.html" {:recursive true})
          :let [t (slurp (str f))]]
    (when (or (re-find #"<script(?![^>]*\bsrc=)(?![^>]*type=\"application/(ld\+)?json\")[^>]*>" t)
              (re-find #"\son[a-z]+=\"" t)
              (re-find #"\sstyle=\"" t)
              (re-find #"<style" t))
      (throw (ex-info (str "inline script/style/handler in " f) {})))))

(defn -main
  "Entry point: cleans dist/web and runs all build steps in dependency order."
  []
  (fs/delete-tree dist)
  (fs/create-dirs dist)
  (build-assets!)
  (build-css!)
  (build-js!)
  (build-html!)
  (check!)
  (println "dist/web built:" (count (filter fs/regular-file? (fs/glob dist "**" {:recursive true}))) "files, base URL" base-url))

(-main)
