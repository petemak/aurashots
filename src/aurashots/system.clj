(ns aurashots.system
  "Integrant wiring and entry point (architecture.md section 2.2). `system-config` builds the component graph:
  config, logger, clock, db, migrations, mailer, media-store, outbox-worker, housekeeping and server. This is
  the one place that connects slices to each other and to the platform: cross-slice functions that would create
  dependency cycles (for example the category-in-use check) are injected here. `-main` starts the server."
  (:gen-class)
  (:require [aurashots.features.auth.api :as auth]
            [aurashots.features.booking.api :as booking]
            [aurashots.features.notifications.api :as notifications]
            [aurashots.features.pages.api :as pages]
            [aurashots.features.portfolio.api :as portfolio]
            [aurashots.features.site.api :as site]
            [aurashots.platform.clock]
            [aurashots.platform.config]
            [aurashots.platform.db]
            [aurashots.platform.http-core.store :as http-store]
            [aurashots.platform.housekeeping]
            [aurashots.platform.logger]
            [aurashots.platform.mailer :as mailer]
            [aurashots.platform.media-store]
            [aurashots.platform.migrations]
            [aurashots.platform.outbox-worker]
            [aurashots.platform.server]
            [aurashots.shared.time :as t]
            [integrant.core :as ig]
            [taoensso.timbre :as log]))

(defn- deliver-tick
  "Outbox tick: sends due messages through the mailer from the contact address."
  [{:keys [conn mailer config now]}]
  (when-let [from (site/contact-email conn (:owner-email config))]
    (notifications/deliver-due! conn (partial mailer/send! mailer) from now)))

(defn- housekeeping-tasks
  "Cleanup tasks: expired idempotency records (7 days), rate counters (1 hour), sessions and tokens."
  []
  [(fn [{:keys [conn now]}]
     (let [ms (t/epoch-ms now)]
       (http-store/purge-expired! conn (- ms (* 7 86400000)) (- ms 3600000))))
   (fn [{:keys [conn now]}] (auth/purge-expired! conn now))])

(defn server-deps
  "Builds the dependency map handed to every slice's `routes` function from resolved components."
  [{:keys [config db clock mailer media-store]}]
  (let [pepper (:token-pepper config)]
    {:conn db :clock clock :config config :mailer mailer :media-store media-store
     :resolve-session (fn [raw now] (auth/resolve-session db pepper raw now))
     :category-in-use? portfolio/category-in-use?
     :sessions-between booking/sessions-between
     :featured-image portfolio/featured-image
     :not-found-fn (fn [req] (pages/not-found-response {:config config} req))}))

(defmethod ig/init-key :aurashots/deps [_ components]
  (server-deps components))

(defn system-config
  "Returns the Integrant configuration map. `opts`: `:overrides` (config overrides, used by tests), `:now-fn`
  (clock function), `:send-fn` (replaces SMTP sending in tests), `:start?` (false to build the connector without
  listening)."
  [{:keys [overrides now-fn send-fn start?]}]
  {:aurashots/config {:overrides overrides}
   :aurashots/logger {:config (ig/ref :aurashots/config)}
   :aurashots/clock {:now-fn now-fn}
   :aurashots/db {:config (ig/ref :aurashots/config) :logger (ig/ref :aurashots/logger)}
   :aurashots/migrations {:db (ig/ref :aurashots/db) :config (ig/ref :aurashots/config) :clock (ig/ref :aurashots/clock)}
   :aurashots/mailer {:config (ig/ref :aurashots/config) :send-fn send-fn}
   :aurashots/media-store {:config (ig/ref :aurashots/config)}
   :aurashots/deps {:config (ig/ref :aurashots/config) :db (ig/ref :aurashots/db) :clock (ig/ref :aurashots/clock)
                    :mailer (ig/ref :aurashots/mailer) :media-store (ig/ref :aurashots/media-store)
                    :migrations (ig/ref :aurashots/migrations)}
   :aurashots/outbox-worker {:db (ig/ref :aurashots/db) :mailer (ig/ref :aurashots/mailer) :clock (ig/ref :aurashots/clock)
                             :config (ig/ref :aurashots/config) :tick-fn deliver-tick
                             :migrations (ig/ref :aurashots/migrations)}
   :aurashots/housekeeping {:db (ig/ref :aurashots/db) :clock (ig/ref :aurashots/clock) :config (ig/ref :aurashots/config)
                            :tasks (housekeeping-tasks) :migrations (ig/ref :aurashots/migrations)}
   :aurashots/server {:deps (ig/ref :aurashots/deps) :start? start?}})

(defn start
  "Starts the whole system with `opts` (see `system-config`) and returns the running Integrant system map."
  [opts]
  (ig/init (system-config opts)))

(defn stop
  "Stops a running system returned by `start`."
  [system]
  (ig/halt! system))

(defn -main
  "Entry point: starts the system and keeps it running until the JVM receives a stop signal (the shutdown hook
  halts the components so the database is flushed)."
  [& _]
  (let [system (start {})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(do (log/info "Stopping") (stop system))))
    @(promise)))
