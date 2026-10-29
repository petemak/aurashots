(ns aurashots.shared.errors
  "Domain failures expressed as data (architecture.md section 5, error shape). Slices throw these with
  `fail!`; the platform error interceptor turns them into `{error:{code,message,fields?}}` responses.
  Shared code: no I/O.")

(def statuses
  "HTTP status for each contract error code (api-contract.md section 1)."
  {:validationFailed 400 :unauthenticated 401 :forbidden 403 :notFound 404 :conflict 409
   :slotUnavailable 409 :itemUnavailable 409 :agreementVersionStale 409
   :idempotencyKeyReused 422 :rateLimited 429 :serverError 500})

(defn fail!
  "Throws an `ex-info` tagged `:aura/error`. `code` is a key of `statuses`, `message` a human sentence,
  `fields` an optional map of field name to message, `extra` optional response headers map."
  ([code message] (fail! code message nil nil))
  ([code message fields] (fail! code message fields nil))
  ([code message fields headers]
   (throw (ex-info message (cond-> {:type :aura/error :code code :status (statuses code)}
                             (seq fields) (assoc :fields fields)
                             headers (assoc :headers headers))))))
