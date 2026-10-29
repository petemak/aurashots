(ns aurashots.features.booking.routes
  "Slice `booking`, HTTP routes (api-contract.md sections 2.7, 2.11, 2.12, 3.3 and 3.9 read view): public
  availability, request submission and status lookup, and the staff endpoints for session requests. There is
  deliberately no route that creates a booking on behalf of a customer."
  (:require [aurashots.features.booking.api :as api]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]
            [aurashots.shared.time :as t]))

(def ^:private submit-schema
  (s/closed-map
   [:customer s/Customer]
   [:contactPreference [:enum "email" "whatsapp"]]
   [:serviceIds [:vector {:min 1 :max 10} s/Id]]
   [:preferredSlot s/Slot]
   [:notes {:optional true} [:maybe (s/text 0 1000)]]
   [:agreement (s/closed-map [:slug (s/text 1 100)] [:version [:int {:min 1}]] [:accepted :boolean])]
   [:website {:optional true} s/Honeypot]))

(def ^:private slot-body
  [[:date s/Date] [:startTime s/Time] [:noteToCustomer {:optional true} [:maybe (s/text 0 1000)]]
   [:force {:optional true} :boolean]])

(defn- availability-query!
  "Validates the cross-field rules of the availability query and returns `{:from :to :date :service-ids}`."
  [{:keys [from to date serviceIds]}]
  (let [from (or from date) to (or to date)
        _ (when-not (and from to) (err/fail! :validationFailed "Give a date, or from and to." {:from "required"}))
        f (t/parse-date from) tt (t/parse-date to)
        ids (when serviceIds (s/csv-ids serviceIds))]
    (when (.isBefore tt f) (err/fail! :validationFailed "The end date is before the start date." {:to "must not be before from"}))
    (when (> (count (t/date-range f tt)) 62) (err/fail! :validationFailed "The range is longer than 62 days." {:to "range too long"}))
    (when (and date (or (.isBefore (t/parse-date date) f) (.isAfter (t/parse-date date) tt)))
      (err/fail! :validationFailed "The date is outside the range." {:date "must be within from and to"}))
    (when (and ids (> (count ids) 10)) (err/fail! :validationFailed "Too many services." {:serviceIds "at most 10"}))
    {:from from :to to :date date :service-ids ids}))

(defn routes
  "Returns the route specs of the booking slice. `deps` keys: `:conn`, `:config`, `:clock`."
  [{:keys [conn] :as deps}]
  (let [staff #{:owner :support}
        id-path {:path (s/closed-map [:id s/Id])}
        action (fn [name path f & [schema]]
                 {:name name :method :post :path path :roles staff
                  :schema (or schema id-path)
                  :handler f})]
    [{:name :booking/availability :method :get :path "/api/availability" :public true
      :schema {:query (s/closed-map [:from {:optional true} s/Date] [:to {:optional true} s/Date]
                                    [:date {:optional true} s/Date] [:serviceIds {:optional true} [:string {:max 500}]])}
      :handler (fn [{{q :query} :params now :now}]
                 (resp/ok (api/availability conn (availability-query! q) now)))}
     {:name :booking/submit :method :post :path "/api/booking-requests" :public true
      :schema {:body submit-schema} :idempotent true :rate-limit :public-post :honeypot true
      :handler (fn [{{body :body} :params :keys [now ip user-agent idempotency]}]
                 (resp/created (api/submit! deps body {:now now :ip ip :user-agent user-agent :done-tx (:done-tx idempotency)})))}
     {:name :booking/public-get :method :get :path "/api/booking-requests/:reference" :public true
      :schema {:path (s/closed-map [:reference (s/text 1 40)]) :query (s/closed-map [:token (s/text 1 200)])}
      :handler (fn [{{{:keys [reference]} :path {:keys [token]} :query} :params}]
                 (resp/ok (or (api/public-by-reference deps reference token)
                              (err/fail! :notFound "We could not find that request."))))}
     {:name :booking/admin-list :method :get :path "/api/admin/booking-requests" :roles staff
      :schema {:query (into [:map {:closed true} [:status {:optional true} [:enum "pending" "confirmed" "declined" "completed"]]]
                            s/Pagination)}
      :handler (fn [{{{:keys [status page pageSize]} :query} :params}]
                 (resp/ok (resp/paginated (api/admin-list conn status) page pageSize)))}
     {:name :booking/admin-get :method :get :path "/api/admin/booking-requests/:id" :roles staff :schema id-path
      :handler (fn [{{{:keys [id]} :path} :params}] (resp/ok (api/admin-detail conn id)))}
     (action :booking/confirm "/api/admin/booking-requests/:id/confirm"
             (fn [{{{:keys [id]} :path body :body} :params user :user now :now}]
               (resp/ok (api/confirm! deps id body (:name user) now)))
             (assoc id-path :body (apply s/closed-map slot-body)))
     (action :booking/reschedule "/api/admin/booking-requests/:id/reschedule"
             (fn [{{{:keys [id]} :path body :body} :params user :user now :now}]
               (resp/ok (api/reschedule! deps id body (:name user) now)))
             (assoc id-path :body (apply s/closed-map slot-body)))
     (action :booking/decline "/api/admin/booking-requests/:id/decline"
             (fn [{{{:keys [id]} :path {:keys [reason]} :body} :params user :user now :now}]
               (resp/ok (api/decline! deps id reason (:name user) now)))
             (assoc id-path :body (s/closed-map [:reason (s/text 1 1000)])))
     (action :booking/mark-paid "/api/admin/booking-requests/:id/mark-paid"
             (fn [{{{:keys [id]} :path body :body} :params user :user now :now}]
               (resp/ok (api/mark-paid! deps id body (:name user) now)))
             (assoc id-path :body (s/closed-map [:method [:enum "cash" "mobileMoney"]] [:amountRwf [:int {:min 1}]])))
     {:name :booking/complete :method :post :path "/api/admin/booking-requests/:id/complete" :roles staff :no-input true
      :handler (fn [{{:keys [id]} :path-params user :user now :now}] (resp/ok (api/complete! deps id (:name user) now)))}
     (action :booking/note "/api/admin/booking-requests/:id/notes"
             (fn [{{{:keys [id]} :path {:keys [text]} :body} :params user :user now :now}]
               (resp/created (api/add-note! deps id text (:name user) now)))
             (assoc id-path :body (s/closed-map [:text (s/text 1 1000)])))
     {:name :booking/resend :method :post :path "/api/admin/booking-requests/:id/messages/:message-id/resend" :roles staff
      :schema {:path (s/closed-map [:id s/Id] [:message-id s/Id])}
      :handler (fn [{{{:keys [id message-id]} :path} :params now :now}] (resp/accepted (api/resend! deps id message-id now)))}
     {:name :booking/calendar :method :get :path "/api/admin/calendar" :roles staff
      :schema {:query (s/closed-map [:from s/Date] [:to s/Date])}
      :handler (fn [{{{:keys [from to]} :query} :params}]
                 (when (> (count (t/date-range (t/parse-date from) (t/parse-date to))) 366)
                   (err/fail! :validationFailed "The range is too long." {:to "at most 366 days"}))
                 (resp/ok (api/calendar-view conn from to)))}]))
