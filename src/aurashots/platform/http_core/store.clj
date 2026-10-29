(ns aurashots.platform.http-core.store
  "Data access for the HTTP layer's own entities: idempotency records and rate-limit counters
  (architecture.md section 3.2, owner `http-core`). The only namespace under http-core that talks to the
  database. Entities: `:idem/*` (key, status :in-flight|:done, createdAt, data `{:hash :status :body}`)
  and `:rate/*` (bucket, windowStart, count)."
  (:require [aurashots.platform.db :as db]
            [datalevin.core :as d]))

(def in-flight-ttl-ms
  "An in-flight idempotency claim older than this is considered abandoned and may be taken over."
  60000)

(defn claim!
  "Atomically claims idempotency `key` for a request with body hash `hash` at epoch-ms `now`.
  Returns `{:state :new}` (claim recorded), `{:state :replay :status s :body b}`, `{:state :in-flight}`
  or `{:state :mismatch}` (same key, different body). Side effect: writes the in-flight marker for :new."
  [conn key hash now]
  (db/in-tx
   conn
   (fn [c]
    (let [e (d/pull (d/db c) '[*] [:idem/key key])]
      (cond
        (nil? (:db/id e))
        (do (d/transact! c [{:idem/key key :idem/status :in-flight :idem/createdAt now :idem/data {:hash hash}}])
            {:state :new})

        (and (= :in-flight (:idem/status e)) (> (- now (:idem/createdAt e)) in-flight-ttl-ms))
        (do (d/transact! c [{:idem/key key :idem/status :in-flight :idem/createdAt now :idem/data {:hash hash}}])
            {:state :new})

        (not= hash (get-in e [:idem/data :hash])) {:state :mismatch}
        (= :in-flight (:idem/status e)) {:state :in-flight}
        :else {:state :replay :status (get-in e [:idem/data :status]) :body (get-in e [:idem/data :body])})))))

(defn done-tx
  "Transaction data that turns the claim for `key` into a completed record holding the response.
  Slices add this to the transaction that creates the booking, order or enquiry so both commit together."
  [key hash now status body]
  [{:idem/key key :idem/status :done :idem/createdAt now :idem/data {:hash hash :status status :body body}}])

(defn release!
  "Removes the claim for `key` when it is still in-flight (the request failed before completing), so the
  client may retry with the same key. Completed records are left untouched."
  [conn key]
  (db/in-tx conn
            (fn [c]
              (when (= :in-flight (:idem/status (d/pull (d/db c) [:idem/status] [:idem/key key])))
                (d/transact! c [[:db/retractEntity [:idem/key key]]])))))

(defn hit!
  "Counts one hit in the fixed window identified by `bucket` (string including the window start) and returns
  the new count. `window-start` is epoch-ms of the window start. Side effect: upserts the counter."
  [conn bucket window-start]
  (db/in-tx conn
            (fn [c]
              (let [n (inc (or (:rate/count (d/pull (d/db c) [:rate/count] [:rate/bucket bucket])) 0))]
                (d/transact! c [{:rate/bucket bucket :rate/windowStart window-start :rate/count n}])
                n))))

(defn count-of
  "Returns the current count of the counter `bucket` (0 when absent)."
  [conn bucket]
  (or (:rate/count (d/pull (d/db conn) [:rate/count] [:rate/bucket bucket])) 0))

(defn purge-expired!
  "Deletes idempotency records created before `idem-cutoff` and rate counters whose window started before
  `rate-cutoff` (both epoch-ms). Returns the number of entities removed."
  [conn idem-cutoff rate-cutoff]
  (let [idem (d/q '[:find [?e ...] :in $ ?c :where [?e :idem/createdAt ?t] [(< ?t ?c)]] (d/db conn) idem-cutoff)
        rate (d/q '[:find [?e ...] :in $ ?c :where [?e :rate/windowStart ?t] [(< ?t ?c)]] (d/db conn) rate-cutoff)
        ids (concat idem rate)]
    (when (seq ids) (d/transact! conn (mapv (fn [e] [:db/retractEntity e]) ids)))
    (count ids)))
