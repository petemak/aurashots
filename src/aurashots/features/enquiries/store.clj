(ns aurashots.features.enquiries.store
  "Slice `enquiries`, data access (architecture.md section 3.2): `enquiry` entities (`:enquiry/id :status :receivedAt`
  and the record in `:enquiry/data`)."
  (:require [aurashots.shared.time :as t]
            [datalevin.core :as d]))

(defn entity
  "The entity map for enquiry record `e`."
  [e]
  {:enquiry/id (:id e) :enquiry/status (:status e) :enquiry/receivedAt (t/epoch-ms (:receivedAt e)) :enquiry/data e})

(defn insert!
  "Writes new enquiry `e` together with `extra-tx` (messages, idempotency record) in ONE transaction."
  [conn e extra-tx]
  (d/transact! conn (into [(entity e)] extra-tx))
  e)

(defn save!
  "Writes updated enquiry `e`."
  [conn e]
  (d/transact! conn [(entity e)])
  e)

(defn get-by-id
  "Returns the enquiry with `id`, or nil."
  [conn id]
  (:enquiry/data (d/pull (d/db conn) [:enquiry/data] [:enquiry/id id])))

(defn list-all
  "Returns enquiries (optionally only with `status`), newest first."
  [conn status]
  (->> (if status
         (d/q '[:find [(pull ?e [:enquiry/data]) ...] :in $ ?s :where [?e :enquiry/status ?s]] (d/db conn) status)
         (d/q '[:find [(pull ?e [:enquiry/data]) ...] :where [?e :enquiry/id _]] (d/db conn)))
       (map :enquiry/data) (sort-by #(- (t/epoch-ms (:receivedAt %)))) vec))

(defn count-by-status
  "Number of enquiries with `status`."
  [conn status]
  (or (d/q '[:find (count ?e) . :in $ ?s :where [?e :enquiry/status ?s]] (d/db conn) status) 0))
