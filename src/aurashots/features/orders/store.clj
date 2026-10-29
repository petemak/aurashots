(ns aurashots.features.orders.store
  "Slice `orders`, data access (architecture.md section 3.2): `merchOrder` entities with indexed attributes
  (`:order/id :reference :status :createdAt :tokenHash`) and the full record in `:order/data`."
  (:require [aurashots.platform.db :as db]
            [aurashots.shared.time :as t]
            [datalevin.core :as d]))

(defn entity
  "The entity map for order record `o`."
  [o]
  {:order/id (:id o) :order/reference (:reference o) :order/status (:status o)
   :order/createdAt (t/epoch-ms (:createdAt o)) :order/tokenHash (:tokenHash o) :order/data o})

(defn insert!
  "Writes the new order `o` with `extra-tx` (acceptance, messages, idempotency record) in ONE transaction."
  [conn o extra-tx]
  (d/transact! conn (into [(entity o)] extra-tx))
  o)

(defn save!
  "Writes the updated order record `o` (plain or transactional connection)."
  [conn o]
  (d/transact! conn [(entity o)])
  o)

(defn transact!
  "Transacts raw `tx-data` on `conn`."
  [conn tx-data]
  (d/transact! conn tx-data))

(defn in-tx
  "Runs `f` with a transactional connection; every read and write inside is atomic and exceptions roll back.
  Returns the value of `f`."
  [conn f]
  (db/in-tx conn f))

(defn get-by-id
  "Returns the order with `id`, or nil."
  [conn id]
  (:order/data (d/pull (d/db conn) [:order/data] [:order/id id])))

(defn get-by-reference
  "Returns the order with public `reference`, or nil."
  [conn reference]
  (:order/data (d/pull (d/db conn) [:order/data] [:order/reference reference])))

(defn reference-exists?
  "True when `reference` is already used."
  [conn reference]
  (boolean (d/q '[:find ?e . :in $ ?r :where [?e :order/reference ?r]] (d/db conn) reference)))

(defn list-all
  "Returns orders (optionally only those with `status`), newest first."
  [conn status]
  (->> (if status
         (d/q '[:find [(pull ?e [:order/data]) ...] :in $ ?s :where [?e :order/status ?s]] (d/db conn) status)
         (d/q '[:find [(pull ?e [:order/data]) ...] :where [?e :order/id _]] (d/db conn)))
       (map :order/data) (sort-by #(- (t/epoch-ms (:createdAt %)))) vec))

(defn count-by-status
  "Number of orders with `status`."
  [conn status]
  (or (d/q '[:find (count ?e) . :in $ ?s :where [?e :order/status ?s]] (d/db conn) status) 0))
