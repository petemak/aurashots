;; Fitness checks F23/F24/F25: slices talk only through api namespaces, the dependency graph matches
;; architecture.md section 2.4 and has no cycles, and the platform does not require slices (except server).
;; Usage: bb scripts/check/boundaries.clj
(require '[clojure.java.io :as io] '[clojure.string :as str] '[clojure.edn :as edn])

(def allowed
  "Section 2.4: slice -> slices it may require (through api). Cross-cutting injection by system.clj is not listed."
  {"site" #{} "media" #{} "merch" #{"media"} "catalog" #{"media" "merch"} "portfolio" #{"media" "site"}
   "storefront" #{"catalog" "merch" "media"} "calendar" #{"site"} "notifications" #{"site"} "documents" #{}
   "auth" #{"notifications"} "booking" #{"catalog" "calendar" "site" "documents" "notifications"}
   "orders" #{"merch" "site" "documents" "notifications"} "enquiries" #{"site" "notifications"}
   "dashboard" #{"booking" "orders" "enquiries"} "pages" #{"site" "catalog" "merch" "storefront" "documents" "media"}})

(defn ns-requires [f]
  (let [form (read-string {:read-cond :allow} (slurp f))
        reqs (some #(when (and (seq? %) (= :require (first %))) (rest %)) (drop 2 form))]
    (map (fn [r] (str (if (sequential? r) (first r) r))) reqs)))

(def files
  "Every .clj source file; editor lock files such as `.#system.clj` (dangling symlinks) are skipped."
  (->> (file-seq (io/file "src")) (filter #(and (str/ends-with? (.getName %) ".clj") (not (str/starts-with? (.getName %) ".#"))))))
(def edges (atom #{}))
(def problems (atom []))

(doseq [f files
        :let [path (.getPath f)
              [_ slice] (re-find #"features/([a-z]+)/" path)]
        r (ns-requires f)]
  (when-let [[_ other part] (re-matches #"aurashots\.features\.([a-z]+)\.([a-z]+)" r)]
    (cond
      (and slice (not= slice other))
      (do (swap! edges conj [slice other])
          (when-not (= "api" part) (swap! problems conj (str path " requires " r " (only .api of other slices is allowed)")))
          (when-not (get-in allowed [slice other] (contains? (allowed slice) other))
            (swap! problems conj (str path ": slice " slice " may not depend on " other))))
      (and (not slice) (str/includes? path "/platform/") (not (str/ends-with? path "server.clj")))
      (swap! problems conj (str path " (platform) requires a slice: " r)))))

(defn cyclic? [edges]
  (let [g (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} edges)]
    (letfn [(reach [n seen] (reduce (fn [s m] (if (s m) s (reach m (conj s m)))) seen (g n #{})))]
      (some (fn [n] ((reach n #{}) n)) (keys g)))))

(when (cyclic? @edges) (swap! problems conj "dependency cycle between slices"))

(if (seq @problems)
  (do (doseq [p @problems] (println "FAIL:" p)) (System/exit 1))
  (println "OK: slice boundaries hold;" (count @edges) "slice dependencies; no cycles"))
