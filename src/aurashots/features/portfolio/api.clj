(ns aurashots.features.portfolio.api
  "Slice `portfolio`, public API for other slices (architecture.md section 2.4): category usage check (injected
  into the settings route) and the featured image for Open Graph tags."
  (:require [aurashots.features.media.api :as media]
            [aurashots.features.portfolio.store :as store]))

(defn category-in-use?
  "True when a portfolio item uses category `category-id`."
  [conn category-id]
  (store/category-in-use? conn category-id))

(defn featured-image
  "Returns the `Image` of the first featured portfolio item (else the first item), or nil when the portfolio is empty."
  [conn]
  (let [items (store/list-all conn)
        item (or (first (filter :featured items)) (first items))]
    (when item (media/image-view conn (:imageId item) (:description item)))))
