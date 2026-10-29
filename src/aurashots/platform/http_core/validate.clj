(ns aurashots.platform.http-core.validate
  "Malli validation and coercion at the HTTP boundary (architecture.md section 5, Validation). A route
  declares schemas for `:path`, `:query`, `:headers` and `:body`; this namespace trims strings, coerces
  (query strings to numbers and booleans), validates, and reports failures as a `validationFailed` error
  with a `fields` map of dotted field name to message."
  (:require [aurashots.shared.errors :as err]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]))

(def ^:private no-trim
  "Keys whose string values must never be trimmed (passwords may legitimately start or end with spaces)."
  #{:password :newPassword :currentPassword})

(defn trim-strings
  "Recursively trims string values in `x`, except those under `no-trim` keys."
  ([x] (trim-strings x nil))
  ([x k]
   (cond
     (string? x) (if (no-trim k) x (str/trim x))
     (map? x) (into {} (map (fn [[kk v]] [kk (trim-strings v kk)])) x)
     (vector? x) (mapv #(trim-strings % k) x)
     :else x)))

(defn- flatten-errors
  "Flattens a humanized Malli error structure into `{\"a.b\" \"first message\"}`."
  ([errs] (flatten-errors errs []))
  ([errs path]
   (cond
     (map? errs) (reduce-kv (fn [acc k v] (merge acc (flatten-errors v (conj path (name k))))) {} errs)
     (and (sequential? errs) (some map? errs))
     (apply merge {} (map-indexed (fn [i v] (when v (flatten-errors v (conj path (str i))))) errs))
     (sequential? errs) {(if (seq path) (str/join "." path) "body") (str (first errs))}
     (string? errs) {(if (seq path) (str/join "." path) "body") errs}
     :else {})))

(defn- humanize
  "Returns a flat `{field message}` map from a Malli explanation."
  [explanation]
  (let [h (me/humanize explanation)]
    (if (or (map? h) (sequential? h) (string? h))
      (let [f (flatten-errors h)]
        (if (empty? f) {"body" "is invalid"} f))
      {"body" "is invalid"})))

(def ^:private transformer
  (mt/transformer mt/string-transformer mt/default-value-transformer))

(defn compile-schema
  "Precompiles a Malli schema into `{:schema s :decode f :explain f}` for repeated use by a route.
  `mode` is :query (string coercion) or :body (JSON values are already typed)."
  [schema mode]
  (let [s (m/schema schema)]
    {:schema s
     :decode (if (= mode :query) (m/decoder s transformer) identity)
     :explain (m/explainer s)}))

(defn check
  "Validates `value` against a compiled schema from `compile-schema`.
  Returns the coerced value, or throws a `validationFailed` error whose `fields` describe every problem."
  [{:keys [decode explain]} value]
  (let [v (decode value)]
    (if-let [ex (explain v)]
      (err/fail! :validationFailed "Some details need attention." (humanize ex))
      v)))
