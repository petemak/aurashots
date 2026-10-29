(ns aurashots.platform.db
  "Platform component `db` (architecture.md section 2.2, section 3): opens and closes the embedded Datalevin
  database under `<data-dir>/db`. The component value is the Datalevin connection; only `store.clj`
  namespaces and the migration runner use it directly."
  (:require [clojure.java.io :as io]
            [datalevin.core :as d]
            [integrant.core :as ig]))

(def bootstrap-schema
  "The only schema known at open time; everything else arrives through versioned migrations."
  {:schema/version {:db/valueType :db.type/long :db/unique :db.unique/identity}
   :schema/appliedAt {:db/valueType :db.type/long}})

(defn open
  "Opens (creating if needed) the Datalevin database in directory `dir` and returns the connection."
  [dir]
  (io/make-parents (io/file dir "x"))
  (d/get-conn (str dir) bootstrap-schema))

(defmethod ig/init-key :aurashots/db [_ {:keys [config]}]
  (open (io/file (:data-dir config) "db")))

(defmethod ig/halt-key! :aurashots/db [_ conn]
  (d/close conn))

(defn in-tx
  "Runs `f` with a transactional connection `c`: every read and write through `c` inside `f` is atomic, writers
  are serialised, and any exception rolls the whole transaction back. Returns the value of `f`."
  [conn f]
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (d/with-transaction [c conn] (f c)))

(defn entity-counts
  "Counts entities per attribute namespace in the database value `db`: `{:entities {\"booking\" 12, \"service\" 6, ...}
  :datoms n}` (`n` is the total number of datoms). Used by the snapshot sidecar and the restore drill."
  [db]
  (let [[by-ns n] (reduce (fn [[m n] dat]
                            [(update m (or (namespace (keyword (:a dat))) "-") (fnil conj #{}) (:e dat)) (inc n)])
                          [{} 0]
                          (d/datoms db :eav))]
    {:entities (into (sorted-map) (map (fn [[k es]] [k (count es)])) by-ns) :datoms n}))

(defn current-counts
  "`entity-counts` of the current state of connection `conn`."
  [conn]
  (entity-counts (d/db conn)))

(defn counts-file
  "The sidecar file `<dest>.counts.edn` that records the entity counts of the snapshot directory `dest`."
  ^java.io.File [dest]
  (io/file (str dest ".counts.edn")))

(defn snapshot!
  "Writes a compacted, consistent copy of the database to the new directory `dest` (Datalevin `copy`) and the sidecar
  file `<dest>.counts.edn` with the entity counts of the very same database value (`{:taken-at :counts}`), so a
  restore drill can compare a restored copy with what was backed up. Side effect: disk I/O. Used by the nightly
  backup."
  [conn dest]
  (io/make-parents (io/file dest "x"))
  (let [db (d/db conn)]
    (d/copy db (str dest) true)
    (spit (counts-file dest) (pr-str {:taken-at (str (java.time.Instant/now)) :counts (entity-counts db)})))
  dest)

(defn export-edn!
  "Writes a logical export of every datom as `[entity attribute value]` EDN vectors (one per line) to file
  `dest`. Slower than a snapshot but readable and independent of the storage format. Side effect: disk I/O."
  [conn dest]
  (io/make-parents dest)
  (with-open [w (io/writer dest)]
    (doseq [dat (d/datoms (d/db conn) :eav)]
      (.write w (pr-str [(:e dat) (:a dat) (:v dat)]))
      (.write w "\n")))
  dest)
