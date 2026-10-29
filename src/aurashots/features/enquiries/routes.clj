(ns aurashots.features.enquiries.routes
  "Slice `enquiries`, HTTP routes (api-contract.md sections 2.15 and 3.10): the public contact form and the staff
  enquiry list."
  (:require [aurashots.features.enquiries.api :as api]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]))

(def ^:private enquiry
  "A staff-facing enquiry row (api-contract.md section 3.10)."
  (rs/obj [:id rs/Id] [:name :string] [:email :string] [:phone (rs/maybe :string)] [:message :string]
          [:receivedAt rs/Instant] [:status [:enum "new" "answered"]] [:whatsappLink (rs/maybe :string)]))

(defn routes
  "Returns the route specs of the enquiries slice. `deps` keys: `:conn`, `:config`."
  [{:keys [conn] :as deps}]
  (let [staff #{:owner :support}]
    [{:name :enquiries/submit :method :post :path "/api/enquiries" :public true
      :schema {:body (s/closed-map [:name (s/text 2 100)] [:email s/Email]
                                   [:phone {:optional true} [:maybe s/Phone]]
                                   [:message (s/text 10 2000)]
                                   [:website {:optional true} s/Honeypot])}
      :idempotent true :rate-limit :public-post :honeypot true
      :response {201 (rs/obj [:id rs/Id] [:receivedAt rs/Instant])}
      :handler (fn [{{body :body} :params :keys [now idempotency]}]
                 (resp/created (api/submit! deps body {:now now :done-tx (:done-tx idempotency)})))}
     {:name :enquiries/admin-list :method :get :path "/api/admin/enquiries" :roles staff
      :schema {:query (into [:map {:closed true} [:status {:optional true} [:enum "new" "answered"]]] s/Pagination)}
      :response {200 (rs/paginated enquiry)}
      :handler (fn [{{{:keys [status page pageSize]} :query} :params}]
                 (resp/ok (resp/paginated (api/admin-list conn status) page pageSize)))}
     {:name :enquiries/answered :method :post :path "/api/admin/enquiries/:id/answered" :roles staff
      :schema {:path (s/closed-map [:id s/Id])} :response {200 enquiry}
      :handler (fn [{{{:keys [id]} :path} :params}] (resp/ok (api/answer! conn id)))}]))
