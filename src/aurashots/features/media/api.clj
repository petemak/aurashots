(ns aurashots.features.media.api
  "Slice `media`, public API for other slices (architecture.md section 2.4): resolve image ids into the
  contract `Image` shape and check that ids exist. Other slices call only this namespace."
  (:require [aurashots.features.media.core :as core]
            [aurashots.features.media.store :as store]))

(def placeholder-id
  "Id of the neutral placeholder image created by the seed migration; used when an item has no image."
  "placeholder-neutral")

(defn image-view
  "Returns the contract `Image` for `image-id` with `alt` text, or nil when the image does not exist."
  [conn image-id alt]
  (some-> (store/get-image conn image-id) (core/image-view alt)))

(defn placeholder-view
  "Returns the neutral placeholder `Image` (alt text states that a real photo is still to be added)."
  [conn]
  (or (image-view conn placeholder-id "Placeholder: a photo will be added soon")
      (core/image-view {:id placeholder-id :width 0 :height 0} "Placeholder: a photo will be added soon")))

(defn images-view
  "Resolves `entries` (`[{:imageId .. :alt ..}]`) into `Image` maps, skipping images that no longer exist."
  [conn entries]
  (vec (keep #(image-view conn (:imageId %) (:alt %)) entries)))

(defn missing-ids
  "Returns the subset of `image-ids` that do not exist."
  [conn image-ids]
  (vec (remove #(store/get-image conn %) image-ids)))
