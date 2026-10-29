(ns aurashots.features.documents.api
  "Slice `documents`, public API for other slices (architecture.md section 2.4): current versions, agreement
  checks and the acceptance transaction data used inside booking and order submission."
  (:require [aurashots.features.documents.core :as core]
            [aurashots.features.documents.store :as store]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]))

(defn current-document
  "Returns `{:doc header :version current-version-record}` for `slug`, or nil when the document is unknown."
  [conn slug]
  (when-let [doc (store/get-doc conn slug)]
    {:doc doc :version (store/get-version conn slug (:currentVersion doc))}))

(defn check-agreement!
  "Validates the customer's `agreement` map for the document `expected-slug`. Returns the accepted (current)
  version record. Throws `validationFailed` or `agreementVersionStale` (409) per api-contract.md section 2.11."
  [conn expected-slug agreement]
  (let [{:keys [version]} (current-document conn expected-slug)]
    (if-let [{:keys [code message fields]} (core/agreement-problem expected-slug agreement version)]
      (err/fail! code message fields)
      version)))

(defn acceptance
  "Builds the acceptance record and its transaction data. `ver` is the version record returned by
  `check-agreement!`; `params` has `:subject-type :subject-id :subject-reference :customer :now :ip :user-agent`.
  Returns `{:record r :tx tx-data}`; the caller commits `tx` together with the booking or order."
  [ver params]
  (let [record (core/acceptance-record ver (assoc params :id (refs/new-id)))]
    {:record record :tx (store/acceptance-tx record)}))

(defn acceptances-for
  "Returns the acceptance records for the public `reference` (booking or order)."
  [conn reference]
  (store/acceptances-for conn reference))

(defn acceptance-summary
  "Customer-facing `{slug version acceptedAt}` form of an acceptance record."
  [record]
  (core/acceptance-summary record))

(defn public-document
  "Returns the public document body for `slug` (and optional older `version`), or nil when unknown."
  [conn slug version]
  (when-let [{:keys [doc] :as cur} (current-document conn slug)]
    (let [ver (if version (store/get-version conn slug version) (:version cur))]
      (when ver (core/public-view doc ver)))))

(defn public-slugs
  "Slugs of the four footer documents that have their own page."
  []
  ["terms-of-sale" "refund-policy" "privacy" "order-terms"])
