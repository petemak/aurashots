;; Writes today's database snapshot and a logical EDN export to BACKUP_DIR. Stop the server first: the embedded
;; database has one owning process (the running server already writes its own nightly snapshot).
;; The host must copy BACKUP_DIR and the uploads directory off the machine (architecture.md section 8).
(require '[babashka.process :as p])
(p/shell "clojure" "-M:dev" "-m" "aurashots.cli" "backup")
(p/shell "clojure" "-M:dev" "-m" "aurashots.cli" "export")
