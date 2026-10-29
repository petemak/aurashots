(ns aurashots.features.orders.routes
  "Slice `orders`, HTTP routes (api-contract.md sections 2.9, 2.13, 2.14 and 3.4): the merchandise quote, order
  submission and status lookup for customers, and the staff order endpoints."
  (:require [aurashots.features.orders.api :as api]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]))

(def ^:private line-schema
  (s/closed-map [:productId s/Id] [:optionIds [:vector {:max 10} s/Id]] [:quantity [:int {:min 1 :max 10}]]))

(def ^:private submit-schema
  (s/closed-map
   [:customer (s/closed-map [:name (s/text 2 100)] [:phone s/Phone] [:email s/Email])]
   [:delivery (s/closed-map [:areaId {:optional true} [:maybe s/Id]] [:areaText {:optional true} [:maybe (s/text 0 100)]]
                            [:address (s/text 5 300)] [:landmark {:optional true} [:maybe (s/text 0 100)]]
                            [:notes {:optional true} [:maybe (s/text 0 500)]])]
   [:items [:vector {:min 1 :max 20} line-schema]]
   [:agreement (s/closed-map [:slug (s/text 1 100)] [:version [:int {:min 1}]] [:accepted :boolean])]
   [:website {:optional true} s/Honeypot]))

(def ^:private order-status [:enum "pending" "approved" "rejected" "paid" "delivered"])

(def ^:private order-line
  "A snapshotted order line (api-contract.md sections 2.13 and 3.4)."
  (rs/obj [:productId rs/Id] [:name :string] [:optionLabels [:vector :string]] [:unitPriceRwf rs/Money]
          [:quantity :int] [:lineTotalRwf rs/Money]))

(def ^:private delivery
  "The delivery block of an order; `areaName` is the snapshot of the chosen area's name."
  (rs/obj [:areaId (rs/maybe :string)] [:areaName (rs/maybe :string)] [:areaText (rs/maybe :string)]
          [:address :string] [:landmark (rs/maybe :string)] [:notes (rs/maybe :string)]))

(def ^:private order-fields
  "Entries shared by the customer and admin order bodies."
  [[:id rs/Id] [:reference :string] [:status order-status] [:createdAt rs/Instant]
   [:items [:vector order-line]] [:subtotalRwf rs/Money] [:deliveryFeeRwf (rs/maybe rs/Money)]
   [:totalRwf (rs/maybe rs/Money)] [:delivery delivery]])

(def ^:private public-order
  "Customer-facing order (api-contract.md sections 2.13 and 2.14); `customerNote` appears for rejected or approved
  orders that carry one."
  (apply rs/obj [:accessToken :string] [:agreementAcceptance rs/PublicAcceptance] (rs/opt :customerNote (rs/maybe :string))
         order-fields))

(def ^:private admin-order
  "Admin order detail (api-contract.md section 3.4)."
  (apply rs/obj [:customer (rs/obj [:name :string] [:phone :string] [:email :string])] [:whatsappLink :string]
         [:customerNote (rs/maybe :string)] [:payment rs/Payment] [:agreementAcceptance (rs/maybe rs/AgreementAcceptance)]
         [:messages [:vector rs/Message]] [:internalNotes [:vector rs/Note]] [:history [:vector rs/HistoryEntry]]
         order-fields))

(def ^:private quote-response
  "Response of `POST /api/quotes/merchandise` (api-contract.md section 2.9)."
  (rs/obj [:items [:vector (rs/obj [:productId rs/Id] [:name :string] [:optionLabels [:vector :string]]
                                   [:unitPriceRwf (rs/maybe rs/Money)] [:quantity :int] [:lineTotalRwf (rs/maybe rs/Money)]
                                   [:available :boolean]
                                   [:reason (rs/maybe [:enum "unpublished" "removed" "soldOut" "invalidOption"])])]]
          [:subtotalRwf rs/Money] [:deliveryFeeRwf (rs/maybe rs/Money)] [:totalRwf (rs/maybe rs/Money)]
          [:feeNote (rs/maybe [:= "toBeConfirmed"])]))

(defn routes
  "Returns the route specs of the orders slice. `deps` keys: `:conn`, `:config`, `:clock`."
  [{:keys [conn] :as deps}]
  (let [staff #{:owner :support}
        id-path {:path (s/closed-map [:id s/Id])}
        post (fn [name path schema f] {:name name :method :post :path path :roles staff :schema schema
                                       :response {200 admin-order} :handler f})]
    [{:name :orders/quote :method :post :path "/api/quotes/merchandise" :public true
      :schema {:body (s/closed-map [:items [:vector {:min 1 :max 20} line-schema]]
                                   [:deliveryAreaId {:optional true} [:maybe s/Id]])}
      :response {200 quote-response}
      :handler (fn [{{{:keys [items deliveryAreaId]} :body} :params}]
                 (resp/ok (api/quote-merchandise conn items deliveryAreaId)))}
     {:name :orders/submit :method :post :path "/api/merchandise-orders" :public true
      :schema {:body submit-schema} :idempotent true :rate-limit :public-post :honeypot true
      :response {201 public-order}
      :handler (fn [{{body :body} :params :keys [now ip user-agent idempotency]}]
                 (resp/created (api/submit! deps body {:now now :ip ip :user-agent user-agent :done-tx (:done-tx idempotency)})))}
     {:name :orders/public-get :method :get :path "/api/merchandise-orders/:reference" :public true
      :schema {:path (s/closed-map [:reference (s/text 1 40)]) :query (s/closed-map [:token (s/text 1 200)])}
      :response {200 public-order}
      :handler (fn [{{{:keys [reference]} :path {:keys [token]} :query} :params}]
                 (resp/ok (or (api/public-by-reference deps reference token)
                              (err/fail! :notFound "We could not find that order."))))}
     {:name :orders/admin-list :method :get :path "/api/admin/merchandise-orders" :roles staff
      :schema {:query (into [:map {:closed true} [:status {:optional true} [:enum "pending" "approved" "paid" "delivered" "rejected"]]]
                            s/Pagination)}
      :response {200 (rs/paginated (rs/obj [:id rs/Id] [:reference :string] [:status order-status] [:customerName :string]
                                           [:itemCount :int] [:totalRwf (rs/maybe rs/Money)] [:createdAt rs/Instant]))}
      :handler (fn [{{{:keys [status page pageSize]} :query} :params}]
                 (resp/ok (resp/paginated (api/admin-list conn status) page pageSize)))}
     {:name :orders/admin-get :method :get :path "/api/admin/merchandise-orders/:id" :roles staff :schema id-path :response {200 admin-order}
      :handler (fn [{{{:keys [id]} :path} :params}] (resp/ok (api/admin-detail conn id)))}
     (post :orders/approve "/api/admin/merchandise-orders/:id/approve"
           (assoc id-path :body (s/closed-map [:deliveryFeeRwf {:optional true} [:maybe [:int {:min 0}]]]
                                              [:noteToCustomer {:optional true} [:maybe (s/text 0 1000)]]))
           (fn [{{{:keys [id]} :path body :body} :params user :user now :now}]
             (resp/ok (api/approve! deps id body (:name user) now))))
     (post :orders/reject "/api/admin/merchandise-orders/:id/reject"
           (assoc id-path :body (s/closed-map [:reason (s/text 1 1000)]))
           (fn [{{{:keys [id]} :path {:keys [reason]} :body} :params user :user now :now}]
             (resp/ok (api/reject! deps id reason (:name user) now))))
     (post :orders/mark-paid "/api/admin/merchandise-orders/:id/mark-paid"
           (assoc id-path :body (s/closed-map [:method [:enum "cash" "mobileMoney"]] [:amountRwf [:int {:min 1}]]))
           (fn [{{{:keys [id]} :path body :body} :params user :user now :now}]
             (resp/ok (api/mark-paid! deps id body (:name user) now))))
     (post :orders/mark-delivered "/api/admin/merchandise-orders/:id/mark-delivered"
           (assoc id-path :body (s/closed-map [:method {:optional true} [:maybe [:enum "cash" "mobileMoney"]]]
                                              [:amountRwf {:optional true} [:maybe [:int {:min 1}]]]))
           (fn [{{{:keys [id]} :path body :body} :params user :user now :now}]
             (resp/ok (api/mark-delivered! deps id body (:name user) now))))
     (assoc (post :orders/note "/api/admin/merchandise-orders/:id/notes"
                  (assoc id-path :body (s/closed-map [:text (s/text 1 1000)]))
                  (fn [{{{:keys [id]} :path {:keys [text]} :body} :params user :user now :now}]
                    (resp/created (api/add-note! deps id text (:name user) now))))
            :response {201 rs/Note})
     (assoc (post :orders/resend "/api/admin/merchandise-orders/:id/messages/:message-id/resend"
                  {:path (s/closed-map [:id s/Id] [:message-id s/Id])}
                  (fn [{{{:keys [id message-id]} :path} :params now :now}]
                    (resp/accepted (api/resend! deps id message-id now))))
            :response {202 rs/Message})]))
