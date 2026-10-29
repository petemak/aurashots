(ns aurashots.features.merch.api
  "Slice `merch`, public API for other slices (architecture.md section 2.4): published product reads, product
  summaries and details, existence checks and order-line resolution. Other slices call only this namespace."
  (:require [aurashots.features.media.api :as media]
            [aurashots.features.merch.core :as core]
            [aurashots.features.merch.store :as store]))

(defn main-image
  "Returns the `Image` of the product's first image, or the neutral placeholder when it has none."
  [conn product]
  (let [{:keys [imageId alt]} (first (:images product))]
    (or (when imageId (media/image-view conn imageId alt))
        (media/placeholder-view conn))))

(defn summary
  "Returns the `ProductSummary` map for the product record `product`."
  [conn product]
  (core/summary product (main-image conn product)))

(defn detail
  "Returns the public detail body (without `relatedServiceIds`) for the product record `product`."
  [conn product]
  (core/detail product (main-image conn product) (media/images-view conn (:images product))))

(defn published-by-slug
  "Returns the published product record with `slug`, or nil."
  [conn slug]
  (let [p (store/get-by-slug conn slug)]
    (when (= :published (:status p)) p)))

(defn list-published
  "Returns published product records filtered by `:category` and `:featured` (both optional) and ordered by
  `:sort` (\"newest\", \"priceAsc\", \"priceDesc\")."
  [conn {:keys [category featured sort]}]
  (->> (store/list-by-status conn [:published])
       (filter #(or (nil? category) (= category (:category %))))
       (filter #(or (nil? featured) (= featured (boolean (:featured %)))))
       (#(core/sort-products % sort))))

(defn summaries-by-ids
  "Returns `ProductSummary` maps for those of `ids` that are published, in the given order."
  [conn ids]
  (vec (keep (fn [id] (when-let [p (store/get-by-id conn id)]
                        (when (= :published (:status p)) (summary conn p))))
             ids)))

(defn existing-ids
  "Returns the subset of product `ids` that exist and are not removed."
  [conn ids]
  (store/ids-existing conn ids))

(defn resolve-lines
  "Resolves order/quote `lines` (`[{:productId :optionIds :quantity}]`) in order. Each result is
  `{:ok true :line {...}}` or `{:ok false :reason r :name n :productId id}` (see `merch.core/resolve-line`)."
  [conn lines]
  (mapv (fn [l] (assoc (core/resolve-line (store/get-by-id conn (:productId l)) l) :productId (:productId l))) lines))

(defn published-slugs
  "Returns the slugs of all published products (for the sitemap)."
  [conn]
  (mapv :slug (store/list-by-status conn [:published])))
