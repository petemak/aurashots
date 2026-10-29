(ns aurashots.platform.http-core.response-schemas
  "Reusable Malli schemas for response bodies (architecture.md section 5, Validation; api-contract.md section 1
  shared shapes). Routes declare `:response {status schema}` with these building blocks. Schemas describe the JSON
  that goes over the wire (keyword keys, ISO strings, nils as null). Object schemas are closed, so a field that the
  contract does not list is reported as a mismatch; use `obj` for them and `maybe` for nullable values."
  (:require [aurashots.platform.http-core.schemas :as s]))

(defn obj
  "A closed `:map` schema from `entries`; a field outside the entries makes the response invalid."
  [& entries]
  (into [:map {:closed true}] entries))

(defn maybe
  "`schema` or null."
  [schema]
  [:maybe schema])

(defn opt
  "An optional entry `[k {:optional true} schema]` for use inside `obj`."
  [k schema]
  [k {:optional true} schema])

(def Instant
  "ISO 8601 instant with offset, e.g. `2026-11-14T10:30:00+02:00`."
  [:re #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$"])

(def Date "Calendar date `YYYY-MM-DD`." s/Date)

(def Time "Time `HH:mm`." s/Time)

(def Id "Opaque id string." :string)

(def Money "Integer RwF, never negative." [:int {:min 0}])

(def Str "Any string." :string)

(def Image
  "Shared shape `Image` (api-contract.md section 1)."
  (obj [:id Id] [:url :string] [:thumbUrl :string] [:width :int] [:height :int] [:alt :string]))

(def Slot
  "Shared shape `Slot`."
  (obj [:date Date] [:startTime Time]))

(def Customer
  "Shared shape `Customer`; `address` appears only when given."
  (obj [:name :string] [:phone :string] [:email :string] (opt :address (maybe :string))))

(def Message
  "Shared shape `Message`."
  (obj [:id Id] [:channel [:= "email"]] [:template :string] [:to :string] [:sentAt (maybe Instant)]
       [:status [:enum "queued" "sent" "failed"]] (opt :error (maybe :string))))

(def Note
  "Internal note `{id text authorName createdAt}` (api-contract.md section 3.3)."
  (obj [:id Id] [:text :string] [:authorName :string] [:createdAt Instant]))

(def HistoryEntry
  "History entry `{at byName action detail}`."
  (obj [:at Instant] [:byName :string] [:action :string] [:detail (maybe :string)]))

(def Payment
  "Payment block of a session request or order."
  (obj [:status [:enum "unpaid" "paid"]] [:method (maybe [:enum "cash" "mobileMoney"])]
       [:amountRwf (maybe Money)] [:paidAt (maybe Instant)]))

(def AgreementAcceptance
  "Admin view of an acceptance record attached to a request or order."
  (obj [:slug :string] [:version :int] [:acceptedAt Instant] [:customerName :string] [:customerEmail :string]
       [:bodyHash :string] [:documentUrl :string]))

(def PublicAcceptance
  "The `agreementAcceptance` block of public responses."
  (obj [:slug :string] [:version :int] [:acceptedAt Instant]))

(def Contents
  "The `contents` block of a service."
  (obj [:editedPictures (maybe :int)] [:videos (maybe :int)] [:outfits (maybe :int)]
       [:deliveryTime (maybe :string)] [:locations (maybe :string)]))

(def Service
  "Public `Service` (api-contract.md section 2.2)."
  (obj [:id Id] [:slug :string] [:name :string] [:shortDescription :string] [:priceRwf Money]
       [:sessionMinutes (maybe :int)] [:featured :boolean] [:mainImage Image] [:contents Contents]))

(def ProductSummary
  "Public `ProductSummary` (api-contract.md section 2.5)."
  (obj [:id Id] [:slug :string] [:name :string] [:category [:enum "clothing" "prints" "framed-art"]]
       [:priceRwf Money] [:mainImage Image] [:isNew :boolean] [:availability [:enum "inStock" "soldOut"]]))

(defn paginated
  "The standard paginated body `{items page pageSize total}` with `item` schema per element."
  [item]
  (obj [:items [:vector item]] [:page :int] [:pageSize :int] [:total :int]))

(defn items
  "The unpaginated list body `{items [item]}`."
  [item]
  (obj [:items [:vector item]]))

(def NoBody
  "Empty body, for 204 responses."
  [:fn {:error/message "must have no body"} nil?])

(def Raw
  "A non-JSON body (HTML page, XML, plain text or a file): only the status is checked."
  :any)
