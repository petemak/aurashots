(ns aurashots.integration.contract-test
  "Contract conformance tests (architecture.md fitness checks F11-F16): every method and path of api-contract.md
  exists in the route table, access metadata is complete, error bodies have the one error shape with a known code,
  and JSON keys and dates follow the conventions."
  (:require [aurashots.platform.server :as server]
            [aurashots.test-helpers :as h :refer [call]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(def env "The running test system." (atom nil))

(use-fixtures :once
  (fn [f]
    (reset! env (h/start-system {:web-root (h/web-root-fixture)}))
    (try (f) (finally (h/stop-system @env)))))

(defn normalise
  "Replaces path parameters of either syntax (`{id}` or `:id`, `*path`) with a placeholder."
  [path]
  (-> path (str/replace #"\{[^}]+\}" ":p") (str/replace #":[A-Za-z-]+" ":p") (str/replace #"\*[a-z]+" "*")))

(def contract-ignored
  "Endpoints mentioned in the contract text only as removed or renamed, not as live routes."
  #{[:get "/api/sitemap"] [:put "/api/admin/services/order"]})

(def contract-extra
  "Routes the contract describes in prose or tables rather than as `METHOD /path` (section 1 Media and section 6)."
  #{[:get "/media/:p/:p"] [:get "/"] [:get "/services"] [:get "/services/:p"] [:get "/portfolio"] [:get "/shop"]
    [:get "/shop/:p"] [:get "/shop/checkout"] [:get "/shop/received/:p"] [:get "/cart/sessions"]
    [:get "/cart/merchandise"] [:get "/book"] [:get "/book/received/:p"] [:get "/about"] [:get "/contact"]
    [:get "/legal/terms-of-sale"] [:get "/legal/refund-policy"] [:get "/legal/privacy"] [:get "/legal/order-terms"]
    [:get "/admin"] [:get "/admin/*"] [:get "/assets/*"] [:get "/css/*"] [:get "/js/*"] [:get "/fonts/*"]})

(defn contract-routes
  "Parses api-contract.md for `METHOD /path` pairs inside backticks."
  []
  (->> (re-seq #"(GET|POST|PUT|PATCH|DELETE) (/[A-Za-z0-9_\-{}/.]*)" (slurp (io/file "api-contract.md")))
       (map (fn [[_ m p]] [(keyword (str/lower-case m)) (normalise (str/replace p #"[.,]$" ""))]))
       (remove contract-ignored)
       set))

(defn live-routes
  "The method/path pairs of the real route table (GET routes also get HEAD twins, excluded here)."
  []
  (->> (server/route-specs {:config {:public-base-url "http://x" :http {}}})
       (map (fn [s] [(:method s) (normalise (:path s))]))
       set))

(deftest ^{:doc "Tests every contract endpoint exists and none extra."} every-contract-endpoint-exists-and-none-extra
  (let [contract (set/union (contract-routes) contract-extra)
        live (live-routes)]
    (is (= #{} (set/difference contract live)) "contract endpoints missing from the route table")
    (is (= #{} (set/difference live contract)) "routes that are not in the contract")))

(deftest ^{:doc "Tests every route declares access and input."} every-route-declares-access-and-input
  (doseq [s (server/route-specs {:config {:public-base-url "http://x" :http {}}})]
    (is (or (:public s) (seq (:roles s))) (str (:name s) " needs :public or :roles"))
    (is (or (:schema s) (:no-input s)) (str (:name s) " needs :schema or :no-input"))
    (when (str/starts-with? (:path s) "/api/admin/")
      (when-not (contains? #{:auth/login :auth/forgot :auth/reset} (:name s))
        (is (seq (:roles s)) (str (:name s) " must require a session"))))))

(deftest ^{:doc "Tests every route declares a response schema."} every-route-declares-a-response-schema
  (doseq [s (server/route-specs {:config {:public-base-url "http://x" :http {}}})]
    (is (seq (:response s)) (str (:name s) " needs :response {status schema}"))
    (is (some #(< % 300) (keys (:response s))) (str (:name s) " needs a success status in :response"))))

(deftest ^{:doc "Tests every admin route rejects anonymous and bad csrf."} every-admin-route-rejects-anonymous-and-bad-csrf
  (let [owner (h/owner-session @env)]
    (doseq [s (server/route-specs {:config {:public-base-url "http://x" :http {}}})
            :when (and (str/starts-with? (:path s) "/api/admin/") (seq (:roles s)))
            :let [path (str/replace (:path s) #":[A-Za-z-]+" "x")]]
      (is (= 401 (:status (call @env (:method s) path))) (str (:method s) " " path))
      (when (not= :get (:method s))
        (is (= 403 (:status (call @env (:method s) path
                                  {:headers {"cookie" (str "session=" (:cookie owner))}})))
            (str "csrf " (:method s) " " path))))))

(def known-codes
  "Error codes of api-contract.md section 1."
  #{"validationFailed" "unauthenticated" "forbidden" "notFound" "conflict" "slotUnavailable"
                   "itemUnavailable" "agreementVersionStale" "idempotencyKeyReused" "rateLimited" "serverError"})

(deftest ^{:doc "Tests errors have one shape."} errors-have-one-shape
  (doseq [r [(call @env :get "/api/nope")
             (call @env :get "/api/services/nope")
             (call @env :get "/api/admin/dashboard")
             (call @env :post "/api/enquiries" {:body {:name "x"} :idem (h/uuid) :ip "70.0.0.1"})
             (call @env :post "/api/enquiries" {:body "{not json" :idem (h/uuid) :ip "70.0.0.1"})
             (call @env :post "/api/quotes/sessions" {:body {:serviceIds []}})]]
    (is (<= 400 (:status r) 499))
    (is (= [:error] (keys (:body r))))
    (is (= #{:code :message} (set/intersection #{:code :message} (set (keys (get-in r [:body :error]))))))
    (is (set/subset? (set (keys (get-in r [:body :error]))) #{:code :message :fields}))
    (is (contains? known-codes (get-in r [:body :error :code])))))

(defn walk-keys
  "Collects every map key in nested JSON data."
  [x]
  (cond (map? x) (concat (map name (keys x)) (mapcat walk-keys (vals x)))
        (sequential? x) (mapcat walk-keys x)
        :else []))

(defn walk-strings
  "Collects every string value in nested JSON data."
  [x]
  (cond (map? x) (mapcat walk-strings (vals x)) (sequential? x) (mapcat walk-strings x) (string? x) [x] :else []))

(deftest ^{:doc "Tests json conventions."} json-conventions
  (let [owner (h/owner-session @env)
        paths ["/api/site-info" "/api/services" "/api/services/single-session" "/api/portfolio" "/api/products"
               "/api/documents/privacy" "/api/availability?from=2026-11-02&to=2026-11-03"]
        admin ["/api/admin/dashboard" "/api/admin/services" "/api/admin/settings" "/api/admin/documents"
               "/api/admin/staff" "/api/admin/booking-requests" "/api/admin/merchandise-orders" "/api/admin/enquiries"
               "/api/admin/calendar?from=2026-11-01&to=2026-11-30" "/api/admin/portfolio" "/api/admin/products"]
        bodies (concat (map #(:body (call @env :get %)) paths)
                       (map #(:body (call @env :get % {:session owner})) admin))]
    (doseq [b bodies]
      (is (map? b))
      (doseq [k (walk-keys b) :when (not (re-matches #"[a-z][a-zA-Z0-9]*" k))]
        ;; Only map keys that are data (ids, dates in field-name maps) may break the pattern.
        (is (re-matches #"[a-z0-9-]+|\d{4}-\d{2}-\d{2}" k) k))
      (doseq [s (walk-strings b)
              :when (re-matches #"\d{4}-\d{2}-\d{2}T.*" s)]
        (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}" s) s)))))
