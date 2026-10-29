(ns aurashots.features.merch.store
  "Slice `merch`, data access (architecture.md section 3.2): `product` entities with indexed attributes
  (`:product/id :slug :status :category :createdAt :imageRefs`) and the whole record in `:product/data`. The
  image refs mirror the images used so `media` can tell whether an image is still referenced."
  (:require [aurashots.shared.time :as t]
            [datalevin.core :as d]))

(defn- ->tx
  "Transaction maps for saving `product`: indexed attributes plus the data blob; image refs are rewritten."
  [product]
  [[:db.fn/retractAttribute [:product/id (:id product)] :product/imageRefs]
   {:product/id (:id product)
    :product/slug (:slug product)
    :product/status (:status product)
    :product/category (:category product)
    :product/createdAt (t/epoch-ms (:createdAt product))
    :product/imageRefs (mapv (fn [i] [:image/id (:imageId i)]) (:images product))
    :product/data product}])

(defn save!
  "Inserts or updates `product` (a full record map). Side effect: one transaction. Returns `product`."
  [conn product]
  (let [exists? (d/q '[:find ?e . :in $ ?id :where [?e :product/id ?id]] (d/db conn) (:id product))]
    (d/transact! conn (if exists? (->tx product) (rest (->tx product)))))
  product)

(defn get-by-id
  "Returns the product record with `id` or nil."
  [conn id]
  (:product/data (d/pull (d/db conn) [:product/data] [:product/id id])))

(defn get-by-slug
  "Returns the product record with `slug` or nil."
  [conn slug]
  (:product/data (d/pull (d/db conn) [:product/data] [:product/slug slug])))

(defn slug-taken?
  "True when any product (including removed ones) already uses `slug`."
  [conn slug]
  (boolean (d/q '[:find ?e . :in $ ?s :where [?e :product/slug ?s]] (d/db conn) slug)))

(defn list-by-status
  "Returns all product records whose status is in the set `statuses`."
  [conn statuses]
  (mapv :product/data
        (d/q '[:find [(pull ?e [:product/data]) ...] :in $ [?s ...] :where [?e :product/status ?s]]
             (d/db conn) (vec statuses))))

(defn ids-existing
  "Returns the subset of `ids` that belong to non-removed products."
  [conn ids]
  (vec (filter (fn [id] (when-let [p (get-by-id conn id)] (not= :removed (:status p)))) ids)))
