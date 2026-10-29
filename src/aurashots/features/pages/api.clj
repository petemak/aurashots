(ns aurashots.features.pages.api
  "Slice `pages`, API used by `system` (architecture.md section 2.3): the fallback response for unknown
  paths, which the base interceptor needs before any route matched."
  (:require [aurashots.features.pages.routes :as routes]))

(defn not-found-response
  "Builds the 404 HTML response for an unmatched `request`; `deps` has `:config`."
  [deps _request]
  (routes/not-found deps))
