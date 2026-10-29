;; Restore drill (fitness check F41, architecture.md section 8): proves that a snapshot can be restored and served.
;;
;; Usage: bb restore-drill [snapshot-dir] [--data-dir DIR] [--backup-dir DIR] [--jar PATH] [--keep]
;;   snapshot-dir  the snapshot to restore (default: the newest db-YYYYMMDD in the backup directory)
;;   --data-dir    the live data directory the snapshot came from (default $DATA_DIR or data); its uploads/ folder is
;;                 copied too, since a restore without the pictures is not a restore
;;   --backup-dir  where to look for snapshots (default $BACKUP_DIR or <data-dir>/backups)
;;   --jar         the uberjar to start (default target/aurashots.jar when it exists, otherwise `clojure -M:run`)
;;   --keep        keep the temporary directory and the server log for inspection
;;
;; It copies the snapshot into a temporary data dir, counts the entities of the restored database, compares them with
;; the counts recorded next to the snapshot (`<snapshot>.counts.edn`, written by the backup; if there is none it
;; falls back to opening the live database, which only works while the server is stopped), starts the application
;; against the copy on a spare port with no mail configured, runs read-only checks (health, site info, services,
;; products, portfolio, the six documents, availability, one picture, admin routes still locked) and prints
;; PASS or FAIL. The temporary directory is removed afterwards. Nothing in the live data is touched, so it is safe to
;; run next to the running server. Exit code 0 on PASS, 1 on FAIL.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[babashka.http-client :as http]
         '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.string :as str])

(def started-at (System/currentTimeMillis))

(defn parse-args
  "Splits the command line into `{:snapshot s :opts {flag value}}`; `--keep` is a boolean flag."
  [args]
  (loop [[a & more] args, out {:opts {}}]
    (cond (nil? a) out
          (= "--keep" a) (recur more (assoc-in out [:opts :keep] true))
          (str/starts-with? a "--") (recur (rest more) (assoc-in out [:opts (keyword (subs a 2))] (first more)))
          :else (recur more (assoc out :snapshot a)))))

(def parsed (parse-args *command-line-args*))
(def opts (:opts parsed))
(def data-dir (or (:data-dir opts) (System/getenv "DATA_DIR") "data"))
(def backup-dir (or (:backup-dir opts) (System/getenv "BACKUP_DIR") (str (fs/path data-dir "backups"))))
(def jar (or (:jar opts) (when (fs/exists? "target/aurashots.jar") "target/aurashots.jar")))

(def results (atom []))
(defn check!
  "Records and prints one check result. `status` is :pass, :fail or :warn."
  [status label detail]
  (swap! results conj [status label])
  (println (format "  %-5s %s%s" (str/upper-case (name status)) label (if detail (str " - " detail) ""))))

(defn die!
  "Prints FAIL with `msg` and exits."
  [msg]
  (println "FAIL:" msg)
  (System/exit 1))

(defn latest-snapshot
  "The newest `db-YYYYMMDD` directory in `dir`, or nil."
  [dir]
  (when (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/directory? %) (re-matches #"db-\d{8}" (str (fs/file-name %)))))
         (map str) sort last)))

(def snapshot (or (:snapshot parsed) (latest-snapshot backup-dir)))
(when-not (and snapshot (fs/directory? snapshot))
  (die! (str "no snapshot found (looked in " backup-dir "). Run `bb backup` first, or pass a snapshot directory.")))

(def prod-flags
  "The production JVM flags (architecture.md section 8)."
  ["-Xmx512m" "--add-opens=java.base/java.nio=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
   "--enable-native-access=ALL-UNNAMED"])

(defn java-cmd
  "Command vector running `aurashots.cli` with `cli-args` (jar if available, otherwise through the clojure CLI)."
  [& cli-args]
  (if jar
    (into (into ["java"] prod-flags) (concat ["-cp" jar "clojure.main" "-m" "aurashots.cli"] cli-args))
    (into ["clojure" "-M:dev" "-m" "aurashots.cli"] cli-args)))

(defn server-cmd
  "Command vector starting the application."
  []
  (if jar
    (into (into ["java"] prod-flags) ["-jar" jar])
    ["clojure" "-J-Xmx512m" "-M:run"]))

(def tmp (str (fs/create-temp-dir {:prefix "aurashots-drill-"})))
(def port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))
(def base (str "http://localhost:" port))
(def drill-env
  "Environment for the processes started by the drill: a throwaway data dir, a spare port, no mail."
  (merge (into {} (System/getenv))
         {"APP_ENV" "dev" "DATA_DIR" tmp "UPLOAD_DIR" (str (fs/path tmp "uploads")) "BACKUP_DIR" (str (fs/path tmp "backups"))
          "PORT" (str port) "PUBLIC_BASE_URL" base "SMTP_USER" "" "SMTP_PASSWORD" "" "OWNER_EMAIL" "" "TOKEN_PEPPER" ""}))

(defn counts-of
  "Runs `aurashots.cli counts` with `env` and returns the parsed counts map, or nil when it failed."
  [env]
  (let [r (apply p/shell {:out :string :err :string :continue true :extra-env env} (java-cmd "counts"))
        line (->> (str/split-lines (:out r)) (filter #(str/starts-with? % "{:entities")) last)]
    (when (and (zero? (:exit r)) line) (edn/read-string line))))

(defn get-json
  "GET `path` from the drill server; returns `{:status :json :headers}`."
  [path]
  (let [r (http/get (str base path) {:throw false :timeout 20000})]
    {:status (:status r) :headers (:headers r)
     :json (when (some-> (get-in r [:headers "content-type"]) (str/starts-with? "application/json"))
             (json/parse-string (:body r) true))}))

(def server (atom nil))
(def log-file (str (fs/path tmp "server.log")))

(defn stop-server!
  "Asks the drill server to stop (SIGTERM, so the database is flushed) and waits up to 30 seconds."
  []
  (when-let [pr @server]
    (.destroy ^Process (:proc pr))
    (deref pr 30000 nil)
    (when (.isAlive ^Process (:proc pr)) (.destroyForcibly ^Process (:proc pr)))
    (reset! server nil)))

(defn run-drill!
  "Runs every step of the drill. Throws only on unexpected errors; check failures are recorded."
  []
  (println "Restore drill")
  (println "  snapshot :" snapshot)
  (println "  temp dir :" tmp)
  (println "  app      :" (if jar (str "uberjar " jar " with production JVM flags") "clojure -M:run"))
  ;; 1. restore the snapshot (and the uploads) into the temporary data dir
  (fs/copy-tree snapshot (fs/path tmp "db"))
  (let [uploads (or (System/getenv "UPLOAD_DIR") (str (fs/path data-dir "uploads")))
        copied-uploads? (fs/directory? uploads)]
    (if copied-uploads?
      (do (fs/copy-tree uploads (fs/path tmp "uploads"))
          (check! :pass "uploads copied" (str (count (fs/glob (fs/path tmp "uploads") "*/*.jpg")) " files")))
      (check! :warn "uploads copied" (str uploads " not found: pictures are not part of this drill")))
    (check! :pass "snapshot restored" (str (fs/size (fs/path tmp "db" "data.mdb")) " bytes in data.mdb"))
    ;; 2. entity counts of the restored copy versus the source
    (let [restored (counts-of drill-env)
          sidecar (str snapshot ".counts.edn")
          source (cond (fs/exists? sidecar) (:counts (edn/read-string (slurp sidecar)))
                       :else (counts-of (assoc drill-env "DATA_DIR" data-dir "UPLOAD_DIR" "" "BACKUP_DIR" "")))]
      (cond
        (nil? restored) (check! :fail "restored database opens and can be counted" nil)
        (nil? source) (check! :warn "entity counts compared with the source"
                              "no counts file next to the snapshot and the live database is locked or missing")
        :else (do (check! :pass "restored database opens and can be counted" (str (:datoms restored) " datoms"))
                  (if (= restored source)
                    (check! :pass "entity counts equal the source"
                            (str (if (fs/exists? sidecar) "recorded at snapshot time: " "live database: ")
                                 (str/join ", " (map (fn [[k v]] (str k " " v)) (:entities restored)))))
                    (check! :fail "entity counts equal the source" (str "source " source " restored " restored)))))
      ;; 3. start the application on the copy
      (reset! server (apply p/process {:out log-file :err :out :extra-env drill-env} (server-cmd)))
      (let [deadline (+ (System/currentTimeMillis) 120000)
            up? (loop []
                  (cond (not (.isAlive ^Process (:proc @server))) false
                        (try (= 200 (:status (get-json "/api/health"))) (catch Exception _ false)) true
                        (> (System/currentTimeMillis) deadline) false
                        :else (do (Thread/sleep 500) (recur))))]
        (if-not up?
          (check! :fail "application starts on the restored data" (str "see " log-file))
          (let [entities (:entities restored)
                n #(get entities % 0)
                health (get-json "/api/health")
                info (get-json "/api/site-info")
                services (get-json "/api/services")
                products (get-json "/api/products")
                portfolio (get-json "/api/portfolio?pageSize=100")
                docs (for [s ["terms-of-sale" "refund-policy" "privacy" "order-terms" "session-booking-agreement" "merchandise-order-terms"]]
                       [s (get-json (str "/api/documents/" s))])
                tomorrow (str (java.time.LocalDate/now (java.time.ZoneId/of "Africa/Kigali")))
                avail (get-json (str "/api/availability?from=" tomorrow "&to=" tomorrow))
                locked (get-json "/api/admin/dashboard")
                svc-n (count (get-in services [:json :items]))
                prod-n (get-in products [:json :total])
                port-n (get-in portfolio [:json :total])]
            (check! :pass "application starts on the restored data" (str "port " port ", " (quot (- (System/currentTimeMillis) started-at) 1000) " s since start of the drill"))
            (check! (if (and (= 200 (:status health)) (= "ok" (get-in health [:json :status]))) :pass :fail) "GET /api/health" nil)
            (check! (if (= 200 (:status info)) :pass :fail) "GET /api/site-info" (get-in info [:json :name]))
            (check! (if (and (= 200 (:status services)) (<= svc-n (n "service")) (or (zero? (n "service")) (pos? svc-n))) :pass :fail)
                    "GET /api/services" (str svc-n " published of " (n "service") " stored"))
            (check! (if (and (= 200 (:status products)) (<= (or prod-n 0) (n "product"))) :pass :fail)
                    "GET /api/products" (str prod-n " published of " (n "product") " stored"))
            (check! (if (and (= 200 (:status portfolio)) (= port-n (n "portfolio"))) :pass :fail)
                    "GET /api/portfolio" (str port-n " items, " (n "portfolio") " stored"))
            (check! (if (every? (fn [[_ r]] (and (= 200 (:status r)) (pos? (get-in r [:json :version] 0)))) docs) :pass :fail)
                    "GET /api/documents/{slug} (6 documents)"
                    (str/join ", " (map (fn [[s r]] (str s " v" (get-in r [:json :version]))) docs)))
            (check! (if (= 200 (:status avail)) :pass :fail) "GET /api/availability" nil)
            (check! (if (= 401 (:status locked)) :pass :fail) "GET /api/admin/dashboard without a session is 401" nil)
            (if-let [img (and copied-uploads? (get-in portfolio [:json :items 0 :image :url]))]
              (let [r (http/get (str base img) {:throw false :timeout 20000})]
                (check! (if (and (= 200 (:status r)) (= "image/jpeg" (get-in r [:headers "content-type"]))) :pass :fail)
                        (str "GET " img) (str (count (:body r)) " bytes")))
              (check! :warn "a picture is served" "skipped: no uploads copied or no portfolio picture")))))
      (stop-server!))))

(try (run-drill!)
     (catch Throwable t (check! :fail "drill ran to the end" (str t)))
     (finally
       (stop-server!)
       (if (:keep opts)
         (println "Kept" tmp "(server log:" log-file ")")
         (fs/delete-tree tmp))))

(let [fails (filter #(= :fail (first %)) @results)
      secs (quot (- (System/currentTimeMillis) started-at) 1000)]
  (println)
  (if (seq fails)
    (do (println (str "FAIL (" (count fails) " failed check" (when (> (count fails) 1) "s") ", " secs " s)")) (System/exit 1))
    (println (str "PASS (" (count @results) " checks, " secs " s)"))))
