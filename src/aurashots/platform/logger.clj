(ns aurashots.platform.logger
  "Platform component `logger` (architecture.md section 2.2, section 5 Logging): configures Timbre to write
  one JSON object per line to stdout. Log calls pass a message and an optional context map; request
  bodies, passwords and tokens are never logged by callers."
  (:require [integrant.core :as ig]
            [jsonista.core :as json]
            [taoensso.timbre :as timbre]))

(defn- output-fn
  "Timbre output function: renders `{ts, level, ns, msg, ...context}` as one JSON line. Exceptions add
  `error` (message) and `errorData` (ex-data) without stack traces for readable single lines."
  [{:keys [instant level ?ns-str vargs ?err]}]
  (let [[msg & more] vargs
        ctx (apply merge (filter map? more))]
    (json/write-value-as-string
     (cond-> (merge {:ts (str instant) :level (name level) :ns ?ns-str
                     :msg (if (string? msg) msg (pr-str msg))}
                    ctx)
       ?err (assoc :error (ex-message ?err) :errorData (pr-str (ex-data ?err)))))))

(defmethod ig/init-key :aurashots/logger [_ {:keys [config]}]
  (let [level (:log-level config)]
    (timbre/merge-config! {:min-level level
                         :output-fn output-fn
                         :appenders {:println {:enabled? true :stream :std-out}}})
    {:level level}))

(defmethod ig/halt-key! :aurashots/logger [_ _] nil)
