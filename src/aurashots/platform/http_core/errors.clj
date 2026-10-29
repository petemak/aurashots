(ns aurashots.platform.http-core.errors
  "Error mapping for the HTTP layer (architecture.md section 5, Error shape): turns any failure into one
  response body `{error:{code,message[,fields]}}` with the contract's status codes."
  (:require [aurashots.shared.errors :as err]
            [taoensso.timbre :as log]))

(defn body
  "Builds the error body map for `code` (keyword), `message` and optional `fields` map."
  [code message fields]
  {:error (cond-> {:code (name code) :message message}
            (seq fields) (assoc :fields (into {} (map (fn [[k v]] [(name k) v]) fields))))})

(defn response
  "Builds a full error response map for `code`, `message`, optional `fields` and extra `headers`."
  ([code message] (response code message nil nil))
  ([code message fields headers]
   {:status (err/statuses code)
    :headers (merge {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"} headers)
    :body (body code message fields)}))

(defn unwrap
  "Returns the original exception when `ex` is Pedestal's interceptor-chain wrapper, else `ex` itself."
  [ex]
  (or (:exception (ex-data ex)) ex))

(defn exception->response
  "Maps an exception to an error response. Domain errors (`:aura/error`) keep their code and message;
  anything else becomes a generic 500 and is logged with the `request-id` and request context `ctx-map`
  (method, path), never with bodies or secrets."
  [ex request-id ctx-map]
  (let [ex (unwrap ex)
        data (ex-data ex)]
    (if (= :aura/error (:type data))
      (response (:code data) (ex-message ex) (:fields data) (:headers data))
      (do (log/error ex "Unhandled error" (assoc ctx-map :requestId request-id))
          (response :serverError "Something went wrong on our side. Please try again." nil nil)))))
