(ns aurashots.platform.migrations
  "Platform component `migrations` (architecture.md section 3.3): applies the versioned files
  `resources/migrations/NNNN-description.edn` once, in order, at startup and records each in
  `:schema/version`. A migration is `{:version n :description s :schema {attr-map} :steps [step ...]}`
  where a step is `{:tx [datoms...]}` or `{:fn qualified-symbol}`; a step function receives
  `{:conn :config :now}` and performs its own writes. Schema changes happen only here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import (java.net URL)
           (java.util.jar JarFile)))

(defn- migration-names
  "Lists the file names found in the `migrations` resource directory (works for directories and jars)."
  []
  (let [^URL url (io/resource "migrations")]
    (case (.getProtocol url)
      "file" (map #(.getName ^java.io.File %) (.listFiles (io/file url)))
      "jar" (let [^java.net.JarURLConnection conn (.openConnection url)
                  ^JarFile jar (.getJarFile conn)]
              (->> (enumeration-seq (.entries jar))
                   (map #(.getName ^java.util.jar.JarEntry %))
                   (filter #(str/starts-with? % "migrations/"))
                   (map #(subs % (count "migrations/"))))))))

(defn load-migrations
  "Reads all migration files, sorted by version. Throws when a file name does not match
  `NNNN-name.edn`, when its `:version` differs from its number, or when numbers are not 1, 2, 3 ... without gaps."
  []
  (let [files (->> (migration-names) (filter #(str/ends-with? % ".edn")) sort)
        migs (mapv (fn [f]
                     (let [[_ n] (re-matches #"^(\d{4})-[a-z0-9-]+\.edn$" f)]
                       (when-not n (throw (ex-info "Bad migration file name" {:file f})))
                       (let [m (edn/read-string (slurp (io/resource (str "migrations/" f))))]
                         (when-not (= (parse-long n) (:version m))
                           (throw (ex-info "Migration version does not match file name" {:file f})))
                         m)))
                   files)]
    (doseq [[i m] (map-indexed vector migs)]
      (when-not (= (inc i) (:version m))
        (throw (ex-info "Migration numbers must be consecutive from 0001" {:version (:version m)}))))
    migs))

(defn applied-versions
  "Returns the set of migration versions already recorded in the database `conn`."
  [conn]
  (set (map first (d/q '[:find ?v :where [_ :schema/version ?v]] (d/db conn)))))

(defn- run-step
  "Executes one migration step against the context map `{:conn :config :now}`."
  [ctx step]
  (cond
    (:tx step) (d/transact! (:conn ctx) (:tx step))
    (:fn step) ((requiring-resolve (:fn step)) ctx)
    :else (throw (ex-info "Unknown migration step" {:step step}))))

(defn migrate!
  "Applies every pending migration in order. `ctx` is `{:conn :config :clock}`. Returns the vector of
  versions applied now. Side effects: schema updates, data writes, one `:schema/version` entity per
  migration, log lines. Throws (and stops) on the first failing migration."
  [{:keys [conn clock] :as ctx}]
  (let [done (applied-versions conn)
        pending (remove #(done (:version %)) (load-migrations))]
    (vec (for [m pending]
           (let [now ((:now-fn clock))]
             (log/info "Applying migration" {:version (:version m) :description (:description m)})
             (when (seq (:schema m)) (d/update-schema conn (:schema m)))
             (doseq [s (:steps m)] (run-step (assoc ctx :now now) s))
             (d/transact! conn [{:schema/version (:version m) :schema/appliedAt (.toEpochMilli now)}])
             (:version m))))))

(defmethod ig/init-key :aurashots/migrations [_ {:keys [db config clock]}]
  {:applied (migrate! {:conn db :config config :clock clock})})
