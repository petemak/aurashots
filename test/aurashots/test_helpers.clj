(ns aurashots.test-helpers
  "Shared helpers for the integration tests: starts the full system on a temporary data directory with a
  controllable clock and a recording mail function, and calls the Pedestal connector in process (no sockets).
  Test fixtures use obviously fake values only."
  (:require [aurashots.features.auth.api :as auth]
            [aurashots.platform.http-core.json :as json]
            [aurashots.system :as system]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.pedestal.connector.test :as pt])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn temp-dir
  "Creates and returns a fresh temporary directory path string."
  []
  (str (Files/createTempDirectory "aurashots-test" (make-array FileAttribute 0))))

(defn start-system
  "Starts the system for tests. `opts`: `:web-root` (string), `:now` (Instant, default 2026-10-29T08:00:00Z),
  `:mail` (fn of message, default records into the returned `:sent` atom). Returns a map with `:system`, `:clock`
  (atom of Instant), `:sent` (atom of sent mails) and `:connector`."
  [& [{:keys [web-root now mail]}]]
  (let [clock (atom (or now (Instant/parse "2026-10-29T08:00:00Z")))
        sent (atom [])
        dir (temp-dir)
        sys (system/start {:overrides (cond-> {:data-dir dir :http {:port 18080 :host "localhost" :trusted-proxy-hops 1}
                                               :token-pepper "test-only-pepper-0123456789abcdef"
                                               :outbox-interval-ms 3600000 :housekeeping-interval-ms 3600000}
                                        web-root (assoc :web-root web-root))
                           :now-fn #(deref clock)
                           :send-fn (or mail (fn [m] (swap! sent conj m) true))
                           :start? false})]
    {:system sys :clock clock :sent sent :dir dir
     :connector (get-in sys [:aurashots/server :connector])}))

(defn stop-system
  "Stops the system started by `start-system`."
  [{:keys [system]}]
  (system/stop system))

(defn parse-body
  "Parses a JSON response body string, or returns it unchanged when it is not JSON."
  [body]
  (if (and (string? body) (or (str/starts-with? body "{") (str/starts-with? body "[")))
    (json/read-bytes (.getBytes ^String body "UTF-8"))
    body))

(defn call
  "Performs a request in process. `env` is the map from `start-system`; `method` a keyword; `opts`: `:body` (map,
  string or InputStream), `:headers` (map of lower-case string to string), `:session` (a map from `login`),
  `:idem` (idempotency key), `:ip` (sent as X-Forwarded-For; the test config trusts one proxy hop).
  Returns `{:status :headers :body}` with the body parsed from JSON when possible."
  [env method path & [{:keys [body headers session idem ip]}]]
  (let [hdrs (cond-> (merge {"content-type" "application/json"} headers)
               session (assoc "cookie" (str "session=" (:cookie session)) "x-csrf-token" (:csrf session))
               idem (assoc "idempotency-key" idem)
               ip (assoc "x-forwarded-for" ip))
        body' (if (map? body) (json/write body) body)
        r (apply pt/response-for (:connector env) method path
                 (cond-> [:headers hdrs] body' (into [:body body'])))]
    (update r :body parse-body)))

(defn create-user!
  "Creates a user directly (owner via the bootstrap API, support via the staff API as the owner) with a known
  `password`, returning a signed-in session map `{:cookie :csrf :user}`. Only for tests."
  [env {:keys [name email role password]}]
  (let [sys (:system env)
        conn (:aurashots/db sys) cfg (:aurashots/config sys)
        pepper (:token-pepper cfg)
        now @(:clock env)]
    (when (= role :owner)
      (let [link (auth/create-owner! {:conn conn :pepper pepper :base-url "http://x" :now now} {:name name :email email})
            token (second (re-find #"token=(.*)$" link))
            r (call env :post "/api/admin/auth/reset-password" {:body {:token token :newPassword password}})]
        (assert (= 204 (:status r)) (str r))))
    (let [r (call env :post "/api/admin/auth/login" {:body {:email email :password password}})
          cookie (second (re-find #"session=([^;]+)" (get-in r [:headers "Set-Cookie"] "")))]
      (assert (= 200 (:status r)) (str r))
      {:cookie cookie :csrf (get-in r [:body :csrfToken]) :user (get-in r [:body :user])})))

(defn owner-session
  "Returns a signed-in session of the owner account, creating the account on first use."
  [env]
  (let [creds {:name "Owner Test" :email "owner@example.test" :password "correct horse battery staple"}]
    (if (= 200 (:status (call env :post "/api/admin/auth/login" {:body (select-keys creds [:email :password]) :ip "127.9.9.9"})))
      (create-user! env creds)
      (create-user! env (assoc creds :role :owner)))))

(defn support-session
  "As `owner`, invites a support user (default email support@example.test), completes the invitation with a known
  password and returns its session."
  [env owner & [email]]
  (let [email (or email "support@example.test")
        r (call env :post "/api/admin/staff" {:session owner :body {:name "Support Test" :email email}})
        _ (assert (= 201 (:status r)) (str r))
        sys (:system env)
        raw (auth/issue-token! (:aurashots/db sys) (get-in sys [:aurashots/config :token-pepper])
                               (get-in r [:body :id]) @(:clock env) (java.time.Duration/ofHours 1))
        r2 (call env :post "/api/admin/auth/reset-password" {:body {:token raw :newPassword "another long password 12"}})
        _ (assert (= 204 (:status r2)) (str r2))
        l (call env :post "/api/admin/auth/login" {:body {:email email :password "another long password 12"}})]
    {:cookie (second (re-find #"session=([^;]+)" (get-in l [:headers "Set-Cookie"])))
     :csrf (get-in l [:body :csrfToken]) :user (get-in l [:body :user])}))

(defn uuid
  "A fresh random UUID string, for idempotency keys."
  []
  (str (random-uuid)))

(defn booking-body
  "A valid booking request body; `overrides` is deep-merged by key at the top level."
  [& [overrides]]
  (merge {:customer {:name "Ann Test" :phone "+250 788 111 222" :email "ann@example.test"}
          :contactPreference "email" :serviceIds ["seed-service-single-session"]
          :preferredSlot {:date "2026-11-20" :startTime "10:30"} :notes nil
          :agreement {:slug "session-booking-agreement" :version 1 :accepted true} :website ""}
         overrides))

(defn web-root-fixture
  "Creates a temporary web root with minimal pages that carry the SEO region and data marker; returns its path."
  []
  (let [root (temp-dir)
        page (fn [body] (str "<!doctype html><html><head><meta charset=\"utf-8\">\n<!--@seo:begin-->\n<title>fallback</title>\n<!--@seo:end-->\n</head><body>" body "</body></html>"))]
    (doseq [[f c] {"site/index.html" (page "<h1>Home</h1>") "site/service.html" (page "<h1>S</h1><!--@data-->")
                   "site/product.html" (page "<h1>P</h1><!--@data-->") "site/contact.html" (page "<h1>C</h1>")
                   "site/legal.html" (page "<h1>L</h1>") "site/services.html" (page "<h1>Services</h1>")
                   "site/book.html" (page "<h1>Book</h1>") "site/404.html" (page "<h1>Not found</h1>")
                   "admin/index.html" "<!doctype html><title>admin</title>" "css/site.abc123.css" "body{}"}]
      (io/make-parents (io/file root f))
      (spit (io/file root f) c))
    root))
