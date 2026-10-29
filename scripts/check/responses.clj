;; Fitness check (response schemas; F53 in architecture.md section 7): every route declares a Malli response schema (architecture.md section 5, Validation).
;; Prints routes that have none and exits non-zero. Every route needs at least one status with a schema, and a route
;; whose success status is 204 must say so (`:response {204 NoBody}`), so that a stray body is caught in dev and test.
;; Usage: bb scripts/check/responses.clj
(require '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str])

(def out (:out (p/shell {:out :string} "clojure" "-M:dev" "-m" "aurashots.cli" "routes")))
(def routes (edn/read-string (->> (str/split-lines out) (drop-while #(not (str/starts-with? % "["))) (str/join "\n"))))

(def bad (remove #(some (fn [s] (< s 300)) (:response %)) routes))
(if (seq bad)
  (do (println "FAIL: routes without a success response schema:")
      (doseq [r bad] (println " " (name (:method r)) (:path r) (:name r)))
      (System/exit 1))
  (println (str "OK: " (count routes) " routes, all declare a response schema")))
