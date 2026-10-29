;; Fitness check F12: every method+path of api-contract.md exists in the route table and none is extra.
;; Usage: bb scripts/check/contract.clj
(require '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.set :as set] '[clojure.string :as str])

(defn normalise [path]
  (-> path (str/replace #"\{[^}]+\}" ":p") (str/replace #":[A-Za-z-]+" ":p") (str/replace #"\*[a-z]+" "*")))

(def ignored #{[:get "/api/sitemap"] [:put "/api/admin/services/order"]})
;; Routes described in prose or in the section 6 table rather than as METHOD /path.
(def prose #{[:get "/media/:p/:p"] [:get "/"] [:get "/services"] [:get "/services/:p"] [:get "/portfolio"] [:get "/shop"]
             [:get "/shop/:p"] [:get "/shop/checkout"] [:get "/shop/received/:p"] [:get "/cart/sessions"]
             [:get "/cart/merchandise"] [:get "/book"] [:get "/book/received/:p"] [:get "/about"] [:get "/contact"]
             [:get "/legal/terms-of-sale"] [:get "/legal/refund-policy"] [:get "/legal/privacy"] [:get "/legal/order-terms"]
             [:get "/admin"] [:get "/admin/*"] [:get "/assets/*"] [:get "/css/*"] [:get "/js/*"] [:get "/fonts/*"]})

(def contract
  (set/union prose
             (->> (re-seq #"(GET|POST|PUT|PATCH|DELETE) (/[A-Za-z0-9_\-{}/.]*)" (slurp "api-contract.md"))
                  (map (fn [[_ m pth]] [(keyword (str/lower-case m)) (normalise (str/replace pth #"[.,]$" ""))]))
                  (remove ignored) set)))

(def out (:out (p/shell {:out :string} "clojure" "-M:dev" "-m" "aurashots.cli" "routes")))
(def live (->> (edn/read-string (->> (str/split-lines out) (drop-while #(not (str/starts-with? % "["))) (str/join "\n")))
               (map (fn [r] [(:method r) (normalise (:path r))])) set))

(def missing (set/difference contract live))
(def extra (set/difference live contract))
(println "missing:" (sort missing))
(println "extra:  " (sort extra))
(if (or (seq missing) (seq extra)) (System/exit 1) (println "OK: contract and route table agree"))
