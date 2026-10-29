(ns aurashots.features.calendar.store
  "Slice `calendar`, data access (architecture.md section 3.2): `block` entities `:block/id`, `:block/date` (indexed
  `YYYY-MM-DD`) and `:block/data` `{:id :date :startTime :endTime :reason :createdByName}`."
  (:require [datalevin.core :as d]))

(defn insert-blocks!
  "Stores the block maps `blocks`. Side effect: one transaction. Returns `blocks`."
  [conn blocks]
  (d/transact! conn (mapv (fn [b] {:block/id (:id b) :block/date (:date b) :block/data b}) blocks))
  blocks)

(defn blocks-between
  "Returns blocks whose date is within `from` and `to` inclusive (ISO date strings compare lexicographically)."
  [conn from to]
  (->> (d/q '[:find [(pull ?e [:block/data]) ...] :in $ ?f ?t
              :where [?e :block/date ?d] [(>= ?d ?f)] [(<= ?d ?t)]]
            (d/db conn) from to)
       (map :block/data)
       (sort-by (juxt :date #(or (:startTime %) "")))
       vec))

(defn delete-block!
  "Deletes block `id`; returns true when it existed. Side effect: one transaction."
  [conn id]
  (let [exists? (d/q '[:find ?e . :in $ ?id :where [?e :block/id ?id]] (d/db conn) id)]
    (when exists? (d/transact! conn [[:db/retractEntity [:block/id id]]]))
    (boolean exists?)))
