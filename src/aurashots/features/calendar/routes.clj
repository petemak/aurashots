(ns aurashots.features.calendar.routes
  "Slice `calendar`, HTTP routes (api-contract.md section 3.9, blocks part): create and delete date and time
  blocks. The calendar read view is served by `booking`, which owns sessions."
  (:require [aurashots.features.calendar.core :as core]
            [aurashots.features.calendar.store :as store]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]))

(def ^:private block
  "A calendar block (api-contract.md section 3.9); `startTime` and `endTime` are null for a whole day."
  (rs/obj [:id rs/Id] [:date rs/Date] [:startTime (rs/maybe rs/Time)] [:endTime (rs/maybe rs/Time)]
          [:reason (rs/maybe :string)] [:createdByName :string]))

(def ^:private created-response
  "Response of `POST /api/admin/calendar/blocks`: the new blocks and the confirmed sessions they overlap."
  (rs/obj [:items [:vector block]]
          [:affectedSessions [:vector (rs/obj [:id rs/Id] [:reference :string] [:date rs/Date] [:startTime rs/Time]
                                              [:endTime rs/Time] [:customerName :string] [:status [:= "confirmed"]])]]))

(defn routes
  "Returns the route specs of the calendar slice. `deps` keys: `:conn`, `:sessions-between` (function
  `(conn from to) -> [{:date :startTime :endTime ..}]` of confirmed sessions, injected from the booking slice)."
  [{:keys [conn sessions-between]}]
  [{:name :calendar/create-blocks :method :post :path "/api/admin/calendar/blocks" :roles #{:owner :support}
    :schema {:body (s/closed-map [:fromDate s/Date] [:toDate {:optional true} [:maybe s/Date]]
                                 [:startTime {:optional true} [:maybe s/Time]] [:endTime {:optional true} [:maybe s/Time]]
                                 [:reason {:optional true} [:maybe (s/text 0 200)]])}
    :response {201 created-response}
    :handler (fn [{{body :body} :params user :user}]
               (let [fields (core/validate-request body)
                     _ (when (seq fields) (err/fail! :validationFailed "Some details need attention." fields))
                     blocks (core/expand body (:name user) refs/new-id)
                     last-date (:date (last blocks))
                     sessions (sessions-between conn (:fromDate body) last-date)]
                 (store/insert-blocks! conn blocks)
                 (resp/created {:items blocks :affectedSessions (core/affected-sessions sessions blocks)})))}
   {:name :calendar/delete-block :method :delete :path "/api/admin/calendar/blocks/:id" :roles #{:owner :support}
    :schema {:path (s/closed-map [:id s/Id])} :response {204 rs/NoBody}
    :handler (fn [{{{:keys [id]} :path} :params}]
               (when-not (store/delete-block! conn id) (err/fail! :notFound "That block does not exist."))
               (resp/no-content))}])
