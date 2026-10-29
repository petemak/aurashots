(ns aurashots.features.booking.store
  "Slice `booking`, data access (architecture.md section 3.2): `bookingRequest` entities with indexed attributes
  (`:booking/id :reference :status :createdAt :tokenHash :confirmedDate :preferredDate`) and the full record
  (customer, lines, payment, notes, history) in `:booking/data`. History and notes are only ever appended by the
  pure core."
  (:require [aurashots.platform.db :as db]
            [aurashots.shared.time :as t]
            [datalevin.core :as d]))

(defn entity
  "The entity map for booking record `b` (indexed attributes plus the data blob)."
  [b]
  (cond-> {:booking/id (:id b) :booking/reference (:reference b) :booking/status (:status b)
           :booking/createdAt (t/epoch-ms (:createdAt b)) :booking/tokenHash (:tokenHash b)
           :booking/preferredDate (get-in b [:preferredSlot :date])
           :booking/data b}
    (get-in b [:confirmedSlot :date]) (assoc :booking/confirmedDate (get-in b [:confirmedSlot :date]))))

(defn insert!
  "Writes `extra-tx` (acceptance, message and idempotency transaction data) together with the new booking
  record `b` in ONE transaction, so a submission is committed exactly once or not at all."
  [conn b extra-tx]
  (d/transact! conn (into [(entity b)] extra-tx))
  b)

(defn save!
  "Writes the updated booking record `b`. Works on a plain connection or inside `in-tx`. When the booking is no
  longer confirmed its `confirmedDate` index entry is cleared."
  [conn b]
  (d/transact! conn (cond-> [(entity b)]
                      (nil? (get-in b [:confirmedSlot :date]))
                      (conj [:db.fn/retractAttribute [:booking/id (:id b)] :booking/confirmedDate])))
  b)

(defn transact!
  "Transacts raw `tx-data` (for example queued messages) on `conn`, a plain or transactional connection."
  [conn tx-data]
  (d/transact! conn tx-data))

(defn in-tx
  "Runs `f` with a transactional connection; every read and write inside is atomic and exceptions roll back.
  Returns the value of `f`."
  [conn f]
  (db/in-tx conn f))

(defn get-by-id
  "Returns the booking record with `id`, or nil."
  [conn id]
  (:booking/data (d/pull (d/db conn) [:booking/data] [:booking/id id])))

(defn get-by-reference
  "Returns the booking record with public `reference`, or nil."
  [conn reference]
  (:booking/data (d/pull (d/db conn) [:booking/data] [:booking/reference reference])))

(defn reference-exists?
  "True when `reference` is already used."
  [conn reference]
  (boolean (d/q '[:find ?e . :in $ ?r :where [?e :booking/reference ?r]] (d/db conn) reference)))

(defn list-all
  "Returns booking records, optionally only those with `status` (keyword), newest first."
  [conn status]
  (->> (if status
         (d/q '[:find [(pull ?e [:booking/data]) ...] :in $ ?s :where [?e :booking/status ?s]] (d/db conn) status)
         (d/q '[:find [(pull ?e [:booking/data]) ...] :where [?e :booking/id _]] (d/db conn)))
       (map :booking/data)
       (sort-by #(- (t/epoch-ms (:createdAt %))))
       vec))

(defn confirmed-between
  "Returns confirmed booking records whose confirmed date is within ISO dates `from` and `to` inclusive."
  [conn from to]
  (->> (d/q '[:find [(pull ?e [:booking/data]) ...] :in $ ?f ?t
              :where [?e :booking/confirmedDate ?d] [(>= ?d ?f)] [(<= ?d ?t)] [?e :booking/status :confirmed]]
            (d/db conn) from to)
       (map :booking/data)
       (sort-by (juxt #(get-in % [:confirmedSlot :date]) #(get-in % [:confirmedSlot :startTime])))
       vec))

(defn pending-between
  "Returns pending booking records whose preferred date is within ISO dates `from` and `to` inclusive."
  [conn from to]
  (->> (d/q '[:find [(pull ?e [:booking/data]) ...] :in $ ?f ?t
              :where [?e :booking/preferredDate ?d] [(>= ?d ?f)] [(<= ?d ?t)] [?e :booking/status :pending]]
            (d/db conn) from to)
       (map :booking/data)
       (sort-by (juxt #(get-in % [:preferredSlot :date]) #(get-in % [:preferredSlot :startTime])))
       vec))

(defn count-by-status
  "Number of bookings with `status` (keyword)."
  [conn status]
  (or (d/q '[:find (count ?e) . :in $ ?s :where [?e :booking/status ?s]] (d/db conn) status) 0))
