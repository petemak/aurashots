(ns aurashots.platform.http-core.schemas
  "Reusable Malli schemas for request bodies and queries (architecture.md section 5, Validation). Slices
  compose these into their per-route schemas. All object schemas are closed: unknown keys are rejected."
  (:require [aurashots.shared.time :as t]
            [clojure.string :as str]))

(def Date
  "A real calendar date `YYYY-MM-DD`."
  [:and {:error/message "must be a date like 2026-11-14"} :string [:fn #(some? (t/parse-date %))]])

(def Time
  "A 24-hour time `HH:mm`."
  [:and {:error/message "must be a time like 10:30"} :string [:fn #(some? (t/parse-time %))]])

(def Email
  "A plausible email address, at most 254 characters."
  [:and {:error/message "must be a valid email address"} :string [:string {:max 254}]
   [:re #"^[^@\s]+@[^@\s]+\.[^@\s]+$"]])

(def Phone
  "A phone number: 7-20 characters, digits, spaces and an optional leading plus."
  [:and {:error/message "must be 7-20 characters: digits, spaces and an optional +"} :string
   [:re #"^\+?[0-9 ]{7,20}$"]])

(def Id
  "An opaque identifier string."
  [:and {:error/message "must be an id"} :string [:string {:min 1 :max 100}]])

(defn text
  "A string schema with trimmed length between `min` and `max` characters (trimming happens before validation)."
  [min max]
  [:string {:min min :max max :error/message (str "must be " min "-" max " characters")}])

(def Slot
  "A calendar slot `{date startTime}`."
  [:map {:closed true} [:date Date] [:startTime Time]])

(def Customer
  "Customer contact details (api-contract.md section 1)."
  [:map {:closed true}
   [:name (text 2 100)] [:phone Phone] [:email Email]
   [:address {:optional true} [:maybe (text 0 300)]]])

(def Honeypot
  "The hidden `website` field: must be absent or an empty string; checked before validation as well."
  [:maybe [:enum ""]])

(def UUIDString
  "A canonical UUID string."
  [:re {:error/message "must be a UUID"} #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"])

(def Pagination
  "Entries to splice into query schemas for `page` and `pageSize`."
  [[:page {:optional true} [:int {:min 1}]]
   [:pageSize {:optional true} [:int {:min 1 :max 100}]]])

(def Bool
  "Query booleans arrive as strings and are decoded by the string transformer."
  :boolean)

(defn closed-map
  "Builds a closed `:map` schema from entries."
  [& entries]
  (into [:map {:closed true}] entries))

(defn csv-ids
  "Splits a comma separated id list into a vector of trimmed non-empty strings."
  [s]
  (->> (str/split (str s) #",") (map str/trim) (remove str/blank?) vec))
