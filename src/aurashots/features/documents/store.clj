(ns aurashots.features.documents.store
  "Slice `documents`, data access (architecture.md section 3.2): `document` headers (`:document/slug`,
  `:document/data`), immutable `documentVersion` entities (`:docver/key` = \"slug@n\") and append-only `acceptance`
  entities. No function here retracts or rewrites a version or an acceptance."
  (:require [datalevin.core :as d]))

(defn get-doc
  "Returns the document header map `{:slug :title :requiresAcceptance :reviewStatus :currentVersion}`, or nil."
  [conn slug]
  (:document/data (d/pull (d/db conn) [:document/data] [:document/slug slug])))

(defn list-docs
  "Returns all document headers ordered by slug."
  [conn]
  (->> (d/q '[:find [(pull ?e [:document/data]) ...] :where [?e :document/slug _]] (d/db conn))
       (map :document/data) (sort-by :slug) vec))

(defn get-version
  "Returns version `n` of document `slug`, or nil."
  [conn slug n]
  (:docver/data (d/pull (d/db conn) [:docver/data] [:docver/key (str slug "@" n)])))

(defn list-versions
  "Returns every version record of `slug`."
  [conn slug]
  (mapv :docver/data (d/q '[:find [(pull ?e [:docver/data]) ...] :in $ ?s :where [?e :docver/slug ?s]] (d/db conn) slug)))

(defn publish-version!
  "Appends version record `ver` and points the document header at it, marking it a draft. Side effect: one
  transaction. Existing versions are never changed."
  [conn doc ver]
  (d/transact! conn [{:docver/key (str (:slug ver) "@" (:version ver)) :docver/slug (:slug ver)
                      :docver/version (:version ver) :docver/data ver}
                     {:document/slug (:slug doc)
                      :document/data (assoc doc :currentVersion (:version ver) :reviewStatus :draft)}])
  ver)

(defn save-doc!
  "Writes the document header `doc` (used for the review flag). Side effect: one transaction."
  [conn doc]
  (d/transact! conn [{:document/slug (:slug doc) :document/data doc}])
  doc)

(defn acceptance-tx
  "Transaction data that appends the acceptance record `acceptance`."
  [acceptance]
  [{:acceptance/id (:id acceptance)
    :acceptance/subjectReference (:subjectReference acceptance)
    :acceptance/subjectId (:subjectId acceptance)
    :acceptance/data acceptance}])

(defn acceptances-for
  "Returns acceptance records whose subject reference is `reference`, oldest first."
  [conn reference]
  (->> (d/q '[:find [(pull ?e [:acceptance/data]) ...] :in $ ?r :where [?e :acceptance/subjectReference ?r]]
            (d/db conn) reference)
       (map :acceptance/data) (sort-by :acceptedAt) vec))

(defn recent-acceptances
  "Returns up to `limit` acceptance records, newest first."
  [conn limit]
  (->> (d/q '[:find [(pull ?e [:acceptance/data]) ...] :where [?e :acceptance/id _]] (d/db conn))
       (map :acceptance/data) (sort-by :acceptedAt #(compare %2 %1)) (take limit) vec))
