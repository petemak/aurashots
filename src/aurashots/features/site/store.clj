(ns aurashots.features.site.store
  "Slice `site`, data access (architecture.md section 3.2): the single `setting` entity, whose attributes
  are `:setting/id` (always \"main\") and `:setting/data` (the whole settings map)."
  (:require [datalevin.core :as d]))

(defn get-settings
  "Returns the settings map stored in `conn`, or nil when no migration has seeded it."
  [conn]
  (:setting/data (d/pull (d/db conn) [:setting/data] [:setting/id "main"])))

(defn save-settings!
  "Replaces the settings map with `settings`. Side effect: one database transaction."
  [conn settings]
  (d/transact! conn [{:setting/id "main" :setting/data settings}])
  settings)
