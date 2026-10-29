(ns aurashots.platform.housekeeping
  "Platform component `housekeeping` (architecture.md section 2.2): on a timer runs the injected cleanup `tasks`
  (expired idempotency records after 7 days, rate counters after 1 hour, expired sessions and tokens), writes
  the nightly database snapshot to `BACKUP_DIR`, keeping the newest 14, and writes the weekly logical export
  (`export-YYYYMMDD.edn`, weekday and hour in Africa/Kigali time from the config, newest 8 kept). Each task is a
  function of `{:conn :now}`; a failing task is logged and never stops the timer."
  (:require [aurashots.platform.db :as db]
            [aurashots.shared.time :as t]
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
        (doseq [d old]
          (doseq [f (reverse (file-seq d))] (.delete ^java.io.File f))
          (.delete (db/counts-file d))))
      dest)))

(def ^:private export-name-re #"export-(\d{8})\.edn")

(defn- export-files
  "The weekly export files in `backup-dir`, newest first."
  [backup-dir]
  (->> (or (.listFiles (io/file backup-dir)) [])
       (filter #(re-matches export-name-re (.getName ^java.io.File %)))
       (sort-by #(.getName ^java.io.File %))
       reverse))

(defn last-export-due
  "Pure. The local (Africa/Kigali) date of the most recent scheduled weekly export at or before `now`: the latest
  date whose weekday is `day` (`:mon`..`:sun`) and whose `hour` has already passed."
  [now {:keys [day hour]}]
  (let [zdt (java.time.ZonedDateTime/ofInstant now t/zone)
        today (.toLocalDate zdt)
        back (loop [d today n 0]
               (if (or (= day (t/day-key d)) (> n 7)) d (recur (.minusDays d 1) (inc n))))]
    (if (and (= back today) (< (.getHour zdt) hour)) (.minusDays back 7) back)))

(defn weekly-export-if-due!
  "Writes `<backup-dir>/export-YYYYMMDD.edn` (the Kigali date of `now`) when no export dated on or after the last
  scheduled export time exists, then prunes to the newest `keep` files. The file is written under a temporary name
  and renamed, so a crash never leaves a partial export that looks complete. Logs one structured line on success
  (`:event :weekly-export :status :ok`) and one error line on failure. Returns the file, or nil when nothing was due."
  [conn backup-dir now {:keys [keep] :as schedule}]
  (let [due (last-export-due now schedule)
        stamp #(.format ^java.time.LocalDate % (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd"))
        newest (some-> (first (export-files backup-dir)) .getName (->> (re-find export-name-re)) second)]
    (when-not (and newest (>= (compare newest (stamp due)) 0))
      (let [started (System/nanoTime)
            dest (io/file backup-dir (str "export-" (stamp (t/today now)) ".edn"))
            tmp (io/file backup-dir (str (.getName dest) ".tmp"))]
        (try
          (db/export-edn! conn tmp)
          (java.nio.file.Files/move (.toPath tmp) (.toPath dest)
                                    (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (doseq [f (drop keep (export-files backup-dir))] (.delete ^java.io.File f))
          (log/info "Weekly export written" {:event "weekly-export" :status "ok" :file (.getName dest)
                                             :bytes (.length dest) :kept keep
                                             :durationMs (quot (- (System/nanoTime) started) 1000000)})
          dest
          (catch Exception e
            (.delete tmp)
            (log/error e "Weekly export failed" {:event "weekly-export" :status "failed" :file (.getName dest)})
            nil))))))

(defn run-once!
  "Runs every task, the snapshot check and the weekly export check once. `ctx` has `:conn :now :tasks :backup-dir`
  and optionally `:weekly-export` (the config schedule; absent means no export). Errors are logged."
  [{:keys [conn now tasks backup-dir weekly-export]}]
  (doseq [t tasks]
    (try (t {:conn conn :now now})
         (catch Exception e (log/error e "Housekeeping task failed"))))
  (try (snapshot-if-due! conn backup-dir now)
       (catch Exception e (log/error e "Database snapshot failed")))
  (when weekly-export (weekly-export-if-due! conn backup-dir now weekly-export)))

(defn daemon-executor
  "A single-thread scheduled executor whose thread is a daemon named `name`."
  ^ScheduledExecutorService [name]
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory (newThread [_ r] (doto (Thread. ^Runnable r ^String name) (.setDaemon true))))))

(defmethod ig/init-key :aurashots/housekeeping [_ {:keys [db clock config tasks]}]
  (let [ex (daemon-executor "housekeeping")]
    (.scheduleWithFixedDelay ex
                             ^Runnable #(run-once! {:conn db :now ((:now-fn clock)) :tasks tasks :backup-dir (:backup-dir config)
                                         :weekly-export (:weekly-export config)})
                             60000 (:housekeeping-interval-ms config) TimeUnit/MILLISECONDS)
    {:executor ex}))

(defmethod ig/halt-key! :aurashots/housekeeping [_ {:keys [executor]}]
  (.shutdownNow ^ScheduledExecutorService executor))
