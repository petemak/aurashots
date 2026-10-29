(ns aurashots.platform.outbox-worker
  "Platform component `outbox-worker` (architecture.md section 2.2): every 15 seconds calls the injected `tick-fn`
  (which hands due outbox messages to the mailer). It does nothing while the mailer is not configured, so with
  email unset messages simply stay queued and the admin shows them as not sent. Errors are logged, never thrown."
  (:require [aurashots.platform.housekeeping :as hk]
            [aurashots.platform.mailer :as mailer]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import (java.util.concurrent ScheduledExecutorService TimeUnit)))

(defn tick!
  "Runs one delivery pass when the mailer is configured. `ctx` has `:db :mailer :clock :config :tick-fn`; the tick
  function receives `{:conn :mailer :config :now}`. Returns the tick function's result or nil."
  [{:keys [db mailer clock config tick-fn]}]
  (when (mailer/configured? mailer)
    (try (tick-fn {:conn db :mailer mailer :config config :now ((:now-fn clock))})
         (catch Exception e (log/error e "Outbox pass failed") nil))))

(defmethod ig/init-key :aurashots/outbox-worker [_ {:keys [config] :as deps}]
  (let [ex (hk/daemon-executor "outbox-worker")]
    (.scheduleWithFixedDelay ex ^Runnable #(tick! deps) 5000 (:outbox-interval-ms config) TimeUnit/MILLISECONDS)
    {:executor ex}))

(defmethod ig/halt-key! :aurashots/outbox-worker [_ {:keys [executor]}]
  (.shutdownNow ^ScheduledExecutorService executor))
