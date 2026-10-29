(ns aurashots.platform.housekeeping
  "Platform component `housekeeping` (architecture.md section 2.2): on a timer runs the injected cleanup `tasks`
  (expired idempotency records after 7 days, rate counters after 1 hour, expired sessions and tokens) and writes
  the nightly database snapshot to `BACKUP_DIR`, keeping the newest 14. Each task is a function of
  `{:conn :now}`; a failing task is logged and never stops the timer."
  (:require [aurashots.platform.db :as db]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import (java.time ZoneOffset)
           (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)))

(defn snapshot-if-due!
  "Writes `<backup-dir>/db-YYYYMMDD` once per UTC day and prunes to the newest 14 snapshots. Returns the new
  directory or nil when today's snapshot already exists."
  [conn backup-dir now]
  (let [day (.format (.atOffset now ZoneOffset/UTC) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd"))
        dest (io/file backup-dir (str "db-" day))]
    (when-not (.exists dest)
      (db/snapshot! conn dest)
      (let [old (->> (.listFiles (io/file backup-dir)) (filter #(re-matches #"db-\d{8}" (.getName ^java.io.File %)))
                     (sort-by #(.getName ^java.io.File %)) reverse (drop 14))]
        (doseq [d old] (doseq [f (reverse (file-seq d))] (.delete ^java.io.File f))))
      dest)))

(defn run-once!
  "Runs every task and the snapshot check once. `ctx` has `:conn :now :tasks :backup-dir`. Errors are logged."
  [{:keys [conn now tasks backup-dir]}]
  (doseq [t tasks]
    (try (t {:conn conn :now now})
         (catch Exception e (log/error e "Housekeeping task failed"))))
  (try (snapshot-if-due! conn backup-dir now)
       (catch Exception e (log/error e "Database snapshot failed"))))

(defn daemon-executor
  "A single-thread scheduled executor whose thread is a daemon named `name`."
  ^ScheduledExecutorService [name]
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory (newThread [_ r] (doto (Thread. ^Runnable r ^String name) (.setDaemon true))))))

(defmethod ig/init-key :aurashots/housekeeping [_ {:keys [db clock config tasks]}]
  (let [ex (daemon-executor "housekeeping")]
    (.scheduleWithFixedDelay ex
                             ^Runnable #(run-once! {:conn db :now ((:now-fn clock)) :tasks tasks :backup-dir (:backup-dir config)})
                             60000 (:housekeeping-interval-ms config) TimeUnit/MILLISECONDS)
    {:executor ex}))

(defmethod ig/halt-key! :aurashots/housekeeping [_ {:keys [executor]}]
  (.shutdownNow ^ScheduledExecutorService executor))
