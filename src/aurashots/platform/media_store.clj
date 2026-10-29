(ns aurashots.platform.media-store
  "Platform component `media-store` (architecture.md section 2.2): reads and writes image derivative files
  under `UPLOAD_DIR` (`<dir>/<imageId>/<size>.jpg`). Writes are atomic (temp file then move) and names are
  validated so no request can escape the directory."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig])
  (:import (java.nio.file Files StandardCopyOption)))

(def ^:private id-re #"[A-Za-z0-9-]{8,64}")

(defn file-for
  "Returns the `java.io.File` for image `id` and `size` keyword (`:thumb :card :large`), or nil when the id
  or size is not a safe name. The file may not exist."
  [store id size]
  (when (and (string? id) (re-matches id-re id) (#{:thumb :card :large} size))
    (io/file (:dir store) id (str (name size) ".jpg"))))

(defn write!
  "Writes the derivative `bytes` for image `id` and `size` atomically. Side effect: creates directories and
  a file. Throws on an unsafe id or I/O failure."
  [store id size ^bytes bytes]
  (let [f (file-for store id size)
        _ (when-not f (throw (ex-info "Unsafe image name" {:id id :size size})))
        tmp (io/file (.getParentFile f) (str (name size) ".tmp"))]
    (io/make-parents f)
    (with-open [o (io/output-stream tmp)] (.write o bytes))
    (Files/move (.toPath tmp) (.toPath f) (into-array [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
    f))

(defn delete!
  "Removes every derivative file of image `id`. Side effect: deletes files; missing files are ignored."
  [store id]
  (when (and (string? id) (re-matches id-re id))
    (doseq [f (reverse (file-seq (io/file (:dir store) id)))] (.delete ^java.io.File f))))

(defmethod ig/init-key :aurashots/media-store [_ {:keys [config]}]
  (let [dir (io/file (:upload-dir config))]
    (.mkdirs dir)
    {:dir dir}))
