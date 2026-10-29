;; Fitness check F44 (static part): booking and order history is append-only. The matching unit test is
;; test/aurashots/features/history_test.clj (run by `bb test`).
;; Fails on a `retract` in the booking or orders slices, other than the index attribute `:booking/confirmedDate`
;; (a query index, not history), and on code that drops, filters or rewrites `:history`.
;; Usage: bb scripts/check/history.clj
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def files (->> (concat (fs/glob "src/aurashots/features/booking" "**.clj") (fs/glob "src/aurashots/features/orders" "**.clj"))
                (remove #(str/starts-with? (str (fs/file-name %)) ".#"))))

(def allowed-retract #":booking/confirmedDate")
(def rewrite #"(dissoc|assoc)[^)]*:history|:history[^)]*(pop|butlast|subvec|drop|remove|filter)")

(def hits
  (for [f files
        [i line] (map-indexed vector (str/split-lines (slurp (str f))))
        :when (or (and (re-find #"retract" line) (not (re-find allowed-retract line)))
                  (re-find rewrite line))]
    (str f ":" (inc i) ": " (str/trim line))))

(if (seq hits)
  (do (println "FAIL: history must only be appended to:") (run! #(println " " %) hits) (System/exit 1))
  (println (str "OK: no retract or rewrite of :history in " (count files) " booking and orders files")))
