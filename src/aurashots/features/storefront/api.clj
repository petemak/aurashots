(ns aurashots.features.storefront.api
  "Slice `storefront`, API for the `pages` slice and its own routes (architecture.md section 2.3): composes the
  service and product detail bodies of api-contract.md sections 2.3 and 2.6, so the page's initial-data block
  and the JSON endpoint are byte-for-byte the same data."
  (:require [aurashots.features.catalog.api :as catalog]
            [aurashots.features.merch.api :as merch]
            [aurashots.features.storefront.core :as core]))

(defn service-detail
  "Returns the full public body for the published service `slug` (service detail, related products and related
  services), or nil when the service is unpublished, unpriced or unknown."
  [conn slug]
  (when-let [s (catalog/published-by-slug conn slug)]
    (assoc (catalog/detail-view conn s)
           :relatedProducts (merch/summaries-by-ids conn (:relatedProductIds s))
           :relatedServices (mapv #(catalog/service-view conn %)
                                  (core/other-services (catalog/published-services conn) (:id s) 3)))))

(defn product-detail
  "Returns the full public body for the published product `slug` (summary, description, details, images, option
  groups and `relatedServiceIds`), or nil when unknown or not published."
  [conn slug]
  (when-let [p (merch/published-by-slug conn slug)]
    (assoc (merch/detail conn p)
           :relatedServiceIds (core/related-service-ids (catalog/published-services conn) (:id p)))))
