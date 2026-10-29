(ns aurashots.features.portfolio.store
  "Slice `portfolio`, data access (architecture.md section 3.2): `portfolioItem` entities with
  `:portfolio/id :sortOrder :image (ref) :categoryId :featured` and the record in `:portfolio/data`."
  (:require [datalevin.core :as d]))

(defn save!
  "Inserts or updates the portfolio item record `item`. Side effect: one transaction. Returns `item`."
  [conn item]
  (d/transact! conn [{:portfolio/id (:id item)
                      :portfolio/sortOrder (long (:sortOrder item))
                      :portfolio/image [:image/id (:imageId item)]
                      :portfolio/categoryId (:categoryId item)
                      :portfolio/featured (boolean (:featured item))
                      :portfolio/data item}])
  item)

(defn get-by-id
  "Returns the item record with `id`, or nil."
  [conn id]
  (:portfolio/data (d/pull (d/db conn) [:portfolio/data] [:portfolio/id id])))

(defn list-all
  "Returns all item records ordered by `sortOrder`, newest first within equal order."
  [conn]
  (->> (d/q '[:find [(pull ?e [:portfolio/data]) ...] :where [?e :portfolio/id _]] (d/db conn))
       (map :portfolio/data)
       (sort-by (juxt :sortOrder #(- (.toEpochMilli ^java.time.Instant (:createdAt %)))))
       vec))

(defn delete!
  "Removes the item `id`. Side effect: one transaction."
  [conn id]
  (d/transact! conn [[:db/retractEntity [:portfolio/id id]]]))

(defn set-order!
  "Sets `sortOrder` of each item in `ids` to its position. Side effect: one transaction."
  [conn ids]
  (d/transact! conn (vec (map-indexed
                          (fn [i id] {:portfolio/id id :portfolio/sortOrder i
                                      :portfolio/data (assoc (get-by-id conn id) :sortOrder i)})
                          ids))))

(defn category-in-use?
  "True when any portfolio item belongs to `category-id`."
  [conn category-id]
  (boolean (d/q '[:find ?e . :in $ ?c :where [?e :portfolio/categoryId ?c]] (d/db conn) category-id)))
