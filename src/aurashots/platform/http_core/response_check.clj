(ns aurashots.platform.http-core.response-check
  "Response-schema checking for the `http-core` platform component (architecture.md section 5, Validation). A route
  spec declares `:response {status malli-schema}`; in `:log` mode (dev) a handler response that does not match is
  logged as an error with the route and the Malli explanation, in `:throw` mode (tests) the response is replaced
  by a 500 that carries the explanation so the test fails loudly, and in `:off` mode (staging, prod) nothing is
  compiled or checked. Bodies are checked as they go over the wire: collections are encoded to JSON and parsed
  back, so Instants, keywords and nils are judged in their JSON form."
  (:require [aurashots.platform.http-core.errors :as errors]
            [aurashots.platform.http-core.json :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [taoensso.timbre :as log]))

(defonce ^{:doc "Names of the routes whose responses were checked in this process (dev and test only); lets a test
  run report routes it never exercised."}
  exercised (atom #{}))

(defn compile-checker
  "Returns a function `(response) -> response` for route `spec` under `mode` (:off, :log or :throw). In :off mode
  the identity function is returned and the schemas are not even compiled."
  [mode {:keys [name method path response]}]
  (if (= :off (or mode :off))
    identity
    (let [explainers (into {} (for [[status schema] response] [status (m/explainer schema)]))]
      (fn [resp]
        (swap! exercised conj name)
        (let [status (:status resp 204)
              body (:body resp)
              explain (get explainers status)
              wire (if (or (map? body) (sequential? body)) (json/read-bytes (.getBytes ^String (json/write body) "UTF-8")) body)
              problem (cond
                        explain (some-> (explain wire) me/humanize)
                        (< status 300) {:status (str "no response schema declared for status " status)})]
          (if-not problem
            resp
            (let [info {:route name :method method :path path :status status :explanation problem}]
              (log/error "Response does not match its schema" info)
              (if (= :throw mode)
                (-> (errors/response :serverError
                                     (str "Response schema mismatch on " name " (" status "): "
                                          (str/trim-newline (with-out-str (pp/pprint problem)))))
                    (assoc :status 500))
                resp))))))))
