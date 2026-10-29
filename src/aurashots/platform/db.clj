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

(defn snapshot!
  "Writes a compacted, consistent copy of the database to the new directory `dest` (Datalevin `copy`). Side effect:
  disk I/O. Used by the nightly backup."
  [conn dest]
  (io/make-parents (io/file dest "x"))
  (d/copy (d/db conn) (str dest) true)
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
