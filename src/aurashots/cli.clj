(ns aurashots.cli
  "Command line tasks run with `clojure -M:run -m aurashots.cli <command>` (see bb.edn): `owner-link` prints a one-time owner
  password reset link, `create-owner --name N
  --email E` creates the single owner account and prints a one-time link for choosing the password, `backup` writes
  a database snapshot, `export` writes a logical EDN export, `counts` prints entity counts (restore drill), and `routes` prints the route table as EDN for the
  fitness checks. Stop the server before `create-owner`, `owner-link`, `backup` and `export`: the embedded database has one
  owning process. This is the only namespace allowed to print to standard output."
  (:require [aurashots.features.auth.api :as auth]
            [aurashots.platform.db :as db]
            [aurashots.platform.housekeeping :as hk]
            [aurashots.platform.server :as server]
            [aurashots.system :as system]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [integrant.core :as ig]))

(defn- arg
  "Value following the option `flag` in `args`, or nil."
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- with-db
  "Initialises config, logger, clock, db and migrations, runs `f` with the resolved components, then halts them."
  [f]
  (let [cfg (system/system-config {})
        sys (ig/init cfg [:aurashots/migrations])]
    (try (f sys) (finally (ig/halt! sys)))))

(defn create-owner
  "Creates the owner account from `--name` and `--email` and prints the password-set link."
  [args]
  (let [name (arg args "--name") email (arg args "--email")]
    (if-not (and name email)
      (do (println "Usage: create-owner --name \"Full Name\" --email owner@example.com") 1)
      (with-db (fn [{:aurashots/keys [db config clock]}]
                 (let [link (auth/create-owner! {:conn db :pepper (:token-pepper config) :base-url (:public-base-url config)
                                                 :now ((:now-fn clock))}
                                                {:name name :email email})]
                   (println "Owner account created. Open this one-time link to choose a password:")
                   (println link)
                   0))))))

(defn owner-link
  "Prints a one-time password reset link (60 minutes) for the owner account."
  [_]
  (with-db (fn [{:aurashots/keys [db config clock]}]
             (if-let [{:keys [url expires-at]} (auth/owner-link! {:conn db :config config :pepper (:token-pepper config)
                                                                  :now ((:now-fn clock))})]
               (do (println "Open this one-time link to choose a new owner password (valid until" (str expires-at) "):")
                   (println url)
                   0)
               (do (println "No owner account exists yet. Run create-owner first.") 1)))))

(defn backup
  "Writes a snapshot of the database into the backup directory and prints its path."
  [_]
  (with-db (fn [{:aurashots/keys [db config clock]}]
             (println (str (or (hk/snapshot-if-due! db (:backup-dir config) ((:now-fn clock))) "Today's snapshot already exists")))
             0)))

(defn export
  "Writes a logical EDN export of the database into the backup directory and prints its path."
  [_]
  (with-db (fn [{:aurashots/keys [db config]}]
             (println (str (db/export-edn! db (io/file (:backup-dir config) "export.edn"))))
             0)))

(defn counts
  "Prints the entity counts of the database under DATA_DIR as EDN (`{:entities {namespace n ..} :datoms n}`). Opens only the
  database component, so it works on a restored copy and makes no change. Used by the restore drill."
  [_]
  (let [sys (ig/init (system/system-config {}) [:aurashots/db])]
    (try (prn (db/current-counts (:aurashots/db sys)))
         (finally (ig/halt! sys)))
    0))

(defn routes
  "Prints the route table as EDN: one map per route with method, path, name, access metadata and the response
  statuses that have a schema."
  [_]
  (let [specs (server/route-specs {:config {:public-base-url "http://localhost" :http {}}})]
    (pp/pprint (mapv #(assoc (select-keys % [:method :path :name :public :roles :no-input :idempotent :rate-limit])
                          :schema? (boolean (:schema %))
                          :response (vec (sort (keys (:response %)))))
                   specs))
    0))

(defn -main
  "Dispatches `command` with the remaining `args` and exits with the command's status code."
  [& [command & args]]
  (let [code (case command
               "create-owner" (create-owner args)
               "owner-link" (owner-link args)
               "backup" (backup args)
               "export" (export args)
               "counts" (counts args)
               "routes" (routes args)
               (do (println "Commands: create-owner, owner-link, backup, export, counts, routes") 1))]
    (shutdown-agents)
    (System/exit code)))
