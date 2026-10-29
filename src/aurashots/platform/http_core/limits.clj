(ns aurashots.platform.http-core.limits
  "Rate limits backed by database counters (api-contract.md section 1, Rate limiting; architecture.md
  section 5). Public creating POSTs: 10 per hour per client IP. Admin login: 5 failed attempts per 15
  minutes per email and IP. Fixed windows; counters expire after one hour (housekeeping)."
  (:require [aurashots.platform.http-core.store :as store]
            [clojure.string :as str]))

(def ^:private hour-ms 3600000)
(def ^:private login-window-ms 900000)

(defn- window-start
  "Start (epoch-ms) of the fixed window of length `len` containing `now`."
  [now len]
  (* len (quot now len)))

(defn client-ip
  "Returns the client address for `request`: the `X-Forwarded-For` entry `hops` positions from the right
  (so only the configured number of trusted proxies are believed), or the socket address when `hops` is 0
  or the header is absent."
  [request hops]
  (let [xff (get-in request [:headers "x-forwarded-for"])
        parts (when (and xff (pos? hops)) (mapv str/trim (str/split xff #",")))]
    (if (seq parts)
      (nth parts (max 0 (- (count parts) hops)))
      (or (:remote-addr request) "unknown"))))

(defn public-post-hit!
  "Counts a public creating POST from `ip` at epoch-ms `now`. Returns `{:allowed? bool :retry-after seconds}`."
  [conn ip now]
  (let [ws (window-start now hour-ms)
        n (store/hit! conn (str "pub|" ip "|" ws) ws)]
    {:allowed? (<= n 10) :retry-after (max 1 (quot (- (+ ws hour-ms) now) 1000))}))

(defn login-blocked?
  "True when `email` from `ip` already has 5 failed logins in the current 15-minute window."
  [conn email ip now]
  (let [ws (window-start now login-window-ms)]
    (>= (store/count-of conn (str "login|" (str/lower-case email) "|" ip "|" ws)) 5)))

(defn login-failure!
  "Records one failed login for `email` from `ip` at epoch-ms `now`."
  [conn email ip now]
  (let [ws (window-start now login-window-ms)]
    (store/hit! conn (str "login|" (str/lower-case email) "|" ip "|" ws) ws)
    nil))

(defn login-retry-after
  "Seconds until the current login window ends."
  [now]
  (max 1 (quot (- (+ (window-start now login-window-ms) login-window-ms) now) 1000)))
