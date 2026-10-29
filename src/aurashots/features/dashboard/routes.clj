(ns aurashots.features.dashboard.routes
  "Slice `dashboard`, HTTP routes (api-contract.md section 3.2): the admin dashboard figures."
  (:require [aurashots.features.dashboard.api :as api]
            [aurashots.platform.http-core.response :as resp]))

(defn routes
  "Returns the route specs of the dashboard slice. `deps` keys: `:conn`."
  [{:keys [conn]}]
  [{:name :dashboard/get :method :get :path "/api/admin/dashboard" :roles #{:owner :support} :no-input true
    :handler (fn [{:keys [now]}] (resp/ok (api/figures conn now)))}])
