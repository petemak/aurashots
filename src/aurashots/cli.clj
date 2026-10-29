(ns aurashots.cli
  "Command line tasks run with `clojure -M:run -m aurashots.cli <command>` (see bb.edn): `create-owner --name N
  --email E` creates the single owner account and prints a one-time link for choosing the password, `backup` writes
  a database snapshot, `export` writes a logical EDN export, and `routes` prints the route table as EDN for the
  fitness checks. Stop the server before `create-owner`, `backup` and `export`: the embedded database has one
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

(defn routes
  "Prints the route table as EDN: one map per route with method, path, name and access metadata."
  [_]
  (let [specs (server/route-specs {:config {:public-base-url "http://localhost" :http {}}})]
    (pp/pprint (mapv #(assoc (select-keys % [:method :path :name :public :roles :no-input :idempotent :rate-limit])
                          :schema? (boolean (:schema %)))
                   specs))
    0))

(defn -main
  "Dispatches `command` with the remaining `args` and exits with the command's status code."
  [& [command & args]]
  (let [code (case command
               "create-owner" (create-owner args)
               "backup" (backup args)
               "export" (export args)
               "routes" (routes args)
               (do (println "Commands: create-owner, backup, export, routes") 1))]
    (shutdown-agents)
    (System/exit code)))
