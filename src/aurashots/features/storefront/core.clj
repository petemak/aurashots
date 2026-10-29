(ns aurashots.features.storefront.core
  "Slice `storefront`, pure logic (architecture.md section 2.3): relations between services and products that
  need both catalogue slices. No I/O.")

(defn related-service-ids
  "Ids of the `services` (stored records) that list `product-id` among their related products."
  [services product-id]
  (->> services (filter #(some #{product-id} (:relatedProductIds %))) (mapv :id)))

(defn other-services
  "Up to `n` services from `services` (stored records) other than the one with `service-id`, keeping order."
  [services service-id n]
  (->> services (remove #(= service-id (:id %))) (take n) vec))
