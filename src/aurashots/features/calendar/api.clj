(ns aurashots.features.calendar.api
  "Slice `calendar`, public API for other slices (architecture.md section 2.4): reading blocks for availability
  and the admin calendar view."
  (:require [aurashots.features.calendar.store :as store]))

(defn blocks-between
  "Returns block maps `{:id :date :startTime :endTime :reason :createdByName}` for ISO dates `from` to `to` inclusive."
  [conn from to]
  (store/blocks-between conn from to))
