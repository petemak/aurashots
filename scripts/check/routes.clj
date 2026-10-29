;; Fitness check F11: default-deny auth and Malli at every boundary.
;; Prints the route table and exits non-zero when a route lacks :public/:roles or an input declaration.
;; Usage: bb scripts/check/routes.clj
(require '[babashka.process :as p] '[clojure.edn :as edn])

(def out (:out (p/shell {:out :string} "clojure" "-M:dev" "-m" "aurashots.cli" "routes")))
(def routes (edn/read-string (->> (clojure.string/split-lines out)
                                  (drop-while #(not (clojure.string/starts-with? % "[")))
                                  (clojure.string/join "\n"))))

(doseq [r routes]
  (println (format "%-7s %-60s %s" (name (:method r)) (:path r)
                   (if (:public r) "public" (str "roles " (:roles r))))))

(def bad (remove #(and (or (:public %) (seq (:roles %))) (or (:schema? %) (:no-input %))) routes))
(if (seq bad)
  (do (println "FAIL: routes without access or input declaration:") (doseq [r bad] (println " " (:name r))) (System/exit 1))
  (println (str "OK: " (count routes) " routes, all declare access and input")))
