;; Restores a snapshot: bb scripts/backend/restore.clj <snapshot-dir>
;; Replaces DATA_DIR/db with the snapshot. Stop the server first. The uploads directory is restored separately
;; by copying the off-host copy back into UPLOAD_DIR (default DATA_DIR/uploads).
(require '[babashka.fs :as fs])
(let [snapshot (first *command-line-args*)
      data-dir (or (System/getenv "DATA_DIR") "data")
      target (str (fs/path data-dir "db"))]
  (when-not (and snapshot (fs/directory? snapshot))
    (println "Usage: bb scripts/backend/restore.clj <snapshot-dir>") (System/exit 1))
  (when (fs/exists? target) (fs/move target (str target ".before-restore-" (System/currentTimeMillis))))
  (fs/copy-tree snapshot target)
  (println "Restored" snapshot "to" target))
