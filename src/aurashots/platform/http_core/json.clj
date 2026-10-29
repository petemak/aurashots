(ns aurashots.platform.http-core.json
  "JSON encoding and decoding for the HTTP layer (architecture.md section 2.2, `http-core`). Keys are
  camelCase keywords inside the application, so no key conversion is needed; `Instant`s are written as
  ISO 8601 with the Africa/Kigali offset."
  (:require [aurashots.shared.time :as t]
            [jsonista.core :as json])
  (:import (com.fasterxml.jackson.core JsonGenerator)
           (java.time Instant)))

(def mapper
  "Shared jsonista object mapper: keyword keys in and out, `Instant` rendered with the business offset."
  (json/object-mapper
   {:decode-key-fn keyword
    :encode-key-fn name
    :encoders {Instant (fn [^Instant i ^JsonGenerator g] (.writeString g ^String (t/instant->str i)))}}))

(defn write
  "Serialises Clojure data to a JSON string."
  ^String [data]
  (json/write-value-as-string data mapper))

(defn read-bytes
  "Parses JSON from `bytes` into Clojure data with keyword keys. Throws on malformed JSON."
  [^bytes bytes]
  (json/read-value bytes mapper))

(defn script-safe
  "Serialises data as JSON that is safe inside an HTML `<script>` element: `<`, `>`, `&` and the
  U+2028/2029 separators are written as unicode escapes."
  ^String [data]
  (-> (write data)
      (.replace "<" "\\u003c")
      (.replace ">" "\\u003e")
      (.replace "&" "\\u0026")
      (.replace " " "\\u2028")
      (.replace " " "\\u2029")))
