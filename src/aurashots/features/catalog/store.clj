(ns aurashots.features.catalog.store
  "Slice `catalog`, data access (architecture.md section 3.2): `service` entities with indexed attributes
  (`:service/id :slug :status :sortOrder :imageRefs`) and the full record in `:service/data`."
  (:require [datalevin.core :as d]))

(defn save!
  "Inserts or updates the service record `service`; rewrites the image refs. Side effect: one transaction.
  Returns `service`."
  [conn service]
  (let [exists? (d/q '[:find ?e . :in $ ?id :where [?e :service/id ?id]] (d/db conn) (:id service))
        entity {:service/id (:id service)
                :service/slug (:slug service)
                :service/status (:status service)
                :service/sortOrder (long (or (:sortOrder service) 0))
                :service/imageRefs (mapv (fn [i] [:image/id (:imageId i)]) (:images service))
                :service/data service}]
    (d/transact! conn (cond-> []
                        exists? (conj [:db.fn/retractAttribute [:service/id (:id service)] :service/imageRefs])
                        true (conj entity))))
  service)

(defn get-by-id
  "Returns the service record with `id`, or nil."
  [conn id]
  (:service/data (d/pull (d/db conn) [:service/data] [:service/id id])))

(defn get-by-slug
  "Returns the service record with `slug`, or nil."
  [conn slug]
  (:service/data (d/pull (d/db conn) [:service/data] [:service/slug slug])))

(defn slug-taken?
  "True when any service (even removed) uses `slug`."
  [conn slug]
  (boolean (d/q '[:find ?e . :in $ ?s :where [?e :service/slug ?s]] (d/db conn) slug)))

(defn list-by-status
  "Returns service records whose status is in `statuses`, ordered by `sortOrder` then name."
  [conn statuses]
  (->> (d/q '[:find [(pull ?e [:service/data]) ...] :in $ [?s ...] :where [?e :service/status ?s]]
            (d/db conn) (vec statuses))
       (map :service/data)
       (sort-by (juxt #(or (:sortOrder %) 0) :name))
       vec))

(defn set-order!
  "Sets `sortOrder` of each service in `ids` to its position (0-based). Side effect: one transaction."
  [conn ids]
  (d/transact! conn (vec (map-indexed
                          (fn [i id]
                            (let [s (get-by-id conn id)]
                              {:service/id id :service/sortOrder i :service/data (assoc s :sortOrder i)}))
                          ids))))
