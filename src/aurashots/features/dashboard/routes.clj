(ns aurashots.features.dashboard.routes
  "Slice `dashboard`, HTTP routes (api-contract.md section 3.2): the admin dashboard figures."
  (:require [aurashots.features.dashboard.api :as api]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]))

(def ^:private figures
  "Response of `GET /api/admin/dashboard` (api-contract.md section 3.2)."
  (rs/obj [:pendingSessionRequests :int] [:pendingOrders :int] [:unansweredEnquiries :int]
          [:upcomingSessions [:vector (rs/obj [:id rs/Id] [:reference :string] [:customerName :string] [:date rs/Date]
                                              [:startTime rs/Time] [:services [:vector :string]])]]))

(defn routes
  "Returns the route specs of the dashboard slice. `deps` keys: `:conn`."
  [{:keys [conn]}]
  [{:name :dashboard/get :method :get :path "/api/admin/dashboard" :roles #{:owner :support} :no-input true
    :response {200 figures}
    :handler (fn [{:keys [now]}] (resp/ok (api/figures conn now)))}])
