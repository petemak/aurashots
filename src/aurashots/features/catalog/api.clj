(ns aurashots.features.catalog.api
  "Slice `catalog`, public API for other slices (architecture.md section 2.4): published services, bookable
  service checks for booking submission, session lengths and sitemap data."
  (:require [aurashots.features.catalog.core :as core]
            [aurashots.features.catalog.store :as store]
            [aurashots.features.media.api :as media]))

(defn main-image
  "Returns the `Image` of the service's first image, or the neutral placeholder when it has none."
  [conn service]
  (let [{:keys [imageId alt]} (first (:images service))]
    (or (when imageId (media/image-view conn imageId alt))
        (media/placeholder-view conn))))

(defn service-view
  "Returns the contract `Service` map for the stored `service`."
  [conn service]
  (core/service-view service (main-image conn service)))

(defn detail-view
  "Returns `Service` plus `description`, `conditions` and `images` (no related items; `storefront` adds those)."
  [conn service]
  (assoc (service-view conn service)
         :description (:description service)
         :conditions (:conditions service)
         :images (media/images-view conn (:images service))))

(defn published-services
  "Returns stored records of services that are published and priced, ordered by `sortOrder`."
  [conn]
  (filterv core/publicly-visible? (store/list-by-status conn [:published])))

(defn published-by-slug
  "Returns the published, priced service record with `slug`, or nil."
  [conn slug]
  (let [s (store/get-by-slug conn slug)]
    (when (and s (core/publicly-visible? s)) s)))

(defn bookable
  "Looks up `service-ids`. Returns `{:services [records in order] :unavailable [names or nil per missing/unbookable]}`:
  a service is unavailable when unknown, removed, unpublished or without a price."
  [conn service-ids]
  (let [found (mapv (fn [id] [id (store/get-by-id conn id)]) service-ids)
        bad (remove (fn [[_ s]] (and s (core/publicly-visible? s))) found)]
    {:services (vec (keep (fn [[_ s]] (when (and s (core/publicly-visible? s)) s)) found))
     :unavailable (mapv (fn [[_ s]] (:name s)) bad)}))

(defn session-minutes
  "Returns the vector of `sessionMinutes` (an int, or nil when the service has none) for the services in
  `service-ids` that exist and are published; unknown ids are skipped."
  [conn service-ids]
  (->> service-ids
       (keep #(store/get-by-id conn %))
       (filter core/publicly-visible?)
       (mapv :sessionMinutes)))

(defn published-slugs
  "Returns the slugs of all published, priced services (for the sitemap)."
  [conn]
  (mapv :slug (published-services conn)))
