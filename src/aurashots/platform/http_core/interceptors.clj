(ns aurashots.platform.http-core.interceptors
  "Shared Pedestal interceptors and the route-spec compiler (architecture.md section 2.2, `http-core`).
  A slice describes a route as a data map
  `{:name kw :method :get :path \"/api/..\" :handler f (:public true | :roles #{..}) (:schema {:path :query :body}
  | :no-input true) :response {status schema} [:idempotent true] [:rate-limit :public-post] [:honeypot true]}`; `compile-route` turns it into
  a Pedestal route vector with the chain rate limit, authentication, role, CSRF, validation, idempotency and
  handler. A route with neither `:public` nor `:roles` makes startup fail (default deny)."
  (:require [aurashots.platform.crypto :as crypto]
            [aurashots.platform.http-core.errors :as errors]
            [aurashots.platform.http-core.json :as json]
            [aurashots.platform.http-core.limits :as limits]
            [aurashots.platform.http-core.response-check :as response-check]
            [aurashots.platform.http-core.store :as store]
            [aurashots.platform.http-core.validate :as validate]
            [aurashots.shared.errors :as err]
            [aurashots.shared.time :as t]
            [clojure.string :as str]
            [io.pedestal.interceptor.chain :as chain]
            [taoensso.timbre :as log])
  (:import (java.io InputStream)))

(def ^:private max-json-bytes (* 1024 1024))

(defn read-body
  "Reads at most `limit` bytes from the request body stream `in` and returns them as a byte array. Throws a
  `validationFailed` error when the stream is longer."
  ^bytes [^InputStream in limit]
  (let [bos (java.io.ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buf)]
        (when (pos? n)
          (when (> (+ total n) limit)
            (err/fail! :validationFailed "The request is too large."))
          (.write bos buf 0 n)
          (recur (+ total n)))))
    (.toByteArray bos)))

(defn- parse-cookies
  "Parses a `Cookie` header into a map of name to value."
  [header]
  (into {} (for [part (str/split (or header "") #";")
                 :let [[k v] (str/split (str/trim part) #"=" 2)]
                 :when (and k v)]
             [k v])))

(defn- canonical
  "Recursively turns maps into sorted maps so equal bodies print identically (idempotency hashing)."
  [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)])) x)
        (vector? x) (mapv canonical x)
        :else x))

(defn request-hash
  "Stable SHA-256 hex of a parsed request body."
  [body]
  (crypto/sha256-hex (pr-str (canonical body))))

(defn- security-headers
  "Headers added to every response. HSTS is added when the public origin is https."
  [https?]
  (cond-> {"X-Content-Type-Options" "nosniff"
           "Referrer-Policy" "strict-origin-when-cross-origin"
           "X-Frame-Options" "DENY"}
    https? (assoc "Strict-Transport-Security" "max-age=31536000")))

(defn- finalize
  "Normalises a handler/error response: JSON-encodes collection bodies, adds ETag handling, security and
  request-id headers, and drops the body for HEAD requests."
  [{:keys [request] :as _ctx} resp https?]
  (let [resp (or resp {:status 204})
        body (:body resp)
        json? (or (map? body) (sequential? body))
        encoded (if json? (json/write body) body)
        etag (when (and (:etag? resp) (string? encoded) (= 200 (:status resp)))
               (str "\"" (subs (crypto/sha256-hex encoded) 0 32) "\""))
        not-modified? (and etag (= etag (get-in request [:headers "if-none-match"])))
        headers (cond-> (merge (security-headers https?) (:headers resp))
                  (and json? (not (some #(= "content-type" (str/lower-case %)) (keys (:headers resp)))))
                  (assoc "Content-Type" "application/json; charset=utf-8")
                  etag (assoc "ETag" etag)
                  (:request-id request) (assoc "X-Request-Id" (:request-id request)))
        resp' (-> (select-keys resp [:status])
                  (assoc :headers headers :body (if not-modified? nil encoded)))
        resp' (if not-modified? (assoc resp' :status 304) resp')]
    (if (= :head (:request-method request)) (assoc resp' :body nil) resp')))

(defn base-interceptor
  "The outermost interceptor. On enter it adds `:request-id`, `:now` (from the clock), `:ip` and `:user-agent`
  to the request; on leave and on error it produces the final response, logs one structured line per request and
  releases an unfinished idempotency claim. `deps` keys: `:conn`, `:clock`, `:config`, `:not-found-fn`
  (request -> response for unmatched paths)."
  [{:keys [conn clock config not-found-fn]}]
  (let [https? (str/starts-with? (:public-base-url config) "https")
        hops (get-in config [:http :trusted-proxy-hops])]
    {:name ::base
     :enter (fn [ctx]
              (let [req (:request ctx)
                    h (:headers req)]
                (-> ctx
                    (assoc ::start (System/nanoTime))
                    (assoc :request (assoc req
                                           :request-id (str (random-uuid))
                                           :now ((:now-fn clock))
                                           :ip (limits/client-ip req hops)
                                           :user-agent (get h "user-agent"))))))
     :leave (fn [ctx]
              (let [req (:request ctx)
                    resp (or (:response ctx)
                             (if (str/starts-with? (str (:uri req)) "/api")
                               (errors/response :notFound "That address does not exist.")
                               (not-found-fn req)))
                    final (finalize ctx resp https?)]
                (when-let [k (get-in ctx [:request :idempotency :key])]
                  (when (>= (:status final) 300) (store/release! conn k)))
                (log/info "request" {:requestId (:request-id req) :method (name (:request-method req))
                                     :path (:uri req) :status (:status final)
                                     :durationMs (quot (- (System/nanoTime) (::start ctx)) 1000000)
                                     :userId (get-in req [:user :id])})
                (assoc ctx :response final)))
     :error (fn [ctx ex]
              (let [req (:request ctx)
                    resp (errors/exception->response ex (:request-id req)
                                                     {:method (some-> (:request-method req) name) :path (:uri req)})
                    final (finalize ctx resp https?)]
                (when-let [k (get-in ctx [:request :idempotency :key])] (store/release! conn k))
                (log/info "request" {:requestId (:request-id req) :method (some-> (:request-method req) name)
                                     :path (:uri req) :status (:status final)})
                (assoc ctx :response final)))}))

(defn- rate-limit-interceptor
  "Counts public creating POSTs per client IP and rejects the 11th within the hour with 429."
  [{:keys [conn]}]
  {:name ::rate-limit
   :enter (fn [ctx]
            (let [{:keys [ip now]} (:request ctx)
                  {:keys [allowed? retry-after]} (limits/public-post-hit! conn ip (t/epoch-ms now))]
              (when-not allowed?
                (err/fail! :rateLimited "Too many requests. Please try again later." nil
                           {"Retry-After" (str retry-after)}))
              ctx))})

(defn- auth-interceptor
  "Authenticates the `session` cookie with `resolve-session`, checks the role and, for state-changing methods,
  the `X-CSRF-Token` header. Throws 401/403 domain errors."
  [{:keys [resolve-session]} roles]
  {:name ::auth
   :enter (fn [ctx]
            (let [req (:request ctx)
                  raw (get (parse-cookies (get-in req [:headers "cookie"])) "session")
                  s (when raw (resolve-session raw (:now req)))]
              (when-not s (err/fail! :unauthenticated "Please sign in."))
              (when-not (contains? roles (get-in s [:user :role]))
                (err/fail! :forbidden "You do not have permission to do that."))
              (when (#{:post :put :patch :delete} (:request-method req))
                (when-not (crypto/constant-time= (:csrf-token s) (get-in req [:headers "x-csrf-token"]))
                  (err/fail! :forbidden "Missing or invalid security token.")))
              (assoc ctx :request (assoc req :user (:user s) :csrf-token (:csrf-token s) :session-id (:session-id s)))))})

(defn- validation-interceptor
  "Parses and validates path, query and body per the route `:schema`. Puts the coerced values in
  `:params {:path :query :body}`. Applies the honeypot rule before schema validation."
  [{:keys [schema honeypot multipart]}]
  (let [compiled (into {} (for [[k s] schema] [k (validate/compile-schema s (if (= k :body) :body :query))]))]
    {:name ::validate
     :enter (fn [ctx]
              (let [req (:request ctx)
                    check (fn [k v] (if-let [c (get compiled k)] (validate/check c v) v))
                    body (when (and (:body compiled) (not multipart))
                           (let [bytes (read-body (:body req) max-json-bytes)
                                 parsed (if (zero? (alength bytes))
                                          {}
                                          (try (json/read-bytes bytes)
                                               (catch Exception _
                                                 (err/fail! :validationFailed "The request body is not valid JSON."))))]
                             (validate/trim-strings parsed)))]
                (when (and honeypot (map? body) (not (str/blank? (str (:website body)))))
                  (err/fail! :validationFailed "We could not process this request."))
                (let [params {:path (check :path (or (:path-params req) {}))
                              :query (check :query (or (:query-params req) {}))
                              :body (when (:body compiled) (check :body body))}]
                  (assoc-in ctx [:request :params] params))))}))

(defn- idempotency-interceptor
  "Implements the `Idempotency-Key` protocol (api-contract.md section 1). The key must be a UUID header.
  Replays stored responses, rejects a changed body (422) and a concurrent duplicate (409 + Retry-After).
  Adds `:idempotency {:key :hash :done-tx (fn [status body] tx-data)}` to the request; the handler includes
  `done-tx` in its creating transaction."
  [{:keys [conn]} endpoint]
  {:name ::idempotency
   :enter (fn [ctx]
            (let [req (:request ctx)
                  k (get-in req [:headers "idempotency-key"])]
              (when-not (and (string? k) (re-matches #"(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}" k))
                (err/fail! :validationFailed "A valid Idempotency-Key header (UUID) is required."
                           {:idempotencyKey "must be a UUID"}))
              (let [full-key (str endpoint "|" (str/lower-case k))
                    h (request-hash (get-in req [:params :body]))
                    now (t/epoch-ms (:now req))
                    {:keys [state status body]} (store/claim! conn full-key h now)]
                (case state
                  :new (assoc-in ctx [:request :idempotency]
                                 {:key full-key :hash h
                                  :done-tx (fn [st b] (store/done-tx full-key h now st b))})
                  :replay (chain/terminate
                           (assoc ctx :response {:status status :body body :headers {"Idempotent-Replay" "true"}}))
                  :in-flight (err/fail! :conflict "That request is still being processed." nil {"Retry-After" "2"})
                  :mismatch (err/fail! :idempotencyKeyReused
                                       "That Idempotency-Key was already used with a different request.")))))})

(defn- handler-interceptor
  "Runs the route handler with a plain request map, passes the returned response map through `check` (response
  schema checking, see `response-check`) and stores it."
  [handler check]
  {:name ::handler
   :enter (fn [ctx]
            (let [req (:request ctx)]
              (assoc ctx :response (check (handler req)))))})

(defn- assert-spec!
  "Fails startup when a route spec lacks access metadata or an input schema declaration."
  [{:keys [name public roles schema no-input] :as spec}]
  (when-not (or public (seq roles))
    (throw (ex-info "Route must declare :public true or :roles" {:route name})))
  (when-not (or schema no-input)
    (throw (ex-info "Route must declare :schema or :no-input true" {:route name})))
  (when-not (and (:method spec) (:path spec) (fn? (:handler spec)))
    (throw (ex-info "Route needs :method, :path and :handler" {:route name})))
  spec)

(defn compile-route
  "Compiles one route spec (see namespace docstring) into a Pedestal table-route vector. `deps` provides `:conn`
  and `:resolve-session`. Throws at startup when the spec is incomplete."
  [deps spec]
  (assert-spec! spec)
  (let [{:keys [name method path handler roles schema idempotent rate-limit]} spec
        check (response-check/compile-checker (get-in deps [:config :response-check]) spec)
        chain (cond-> []
                rate-limit (conj (rate-limit-interceptor deps))
                (seq roles) (conj (auth-interceptor deps roles))
                schema (conj (validation-interceptor spec))
                idempotent (conj (idempotency-interceptor deps (clojure.core/name name)))
                true (conj (handler-interceptor handler check)))]
    [path method chain :route-name name]))
