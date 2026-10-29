(ns aurashots.features.calendar.routes
  "Slice `calendar`, HTTP routes (api-contract.md section 3.9, blocks part): create and delete date and time
  blocks. The calendar read view is served by `booking`, which owns sessions."
  (:require [aurashots.features.calendar.core :as core]
            [aurashots.features.calendar.store :as store]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]))

(defn routes
  "Returns the route specs of the calendar slice. `deps` keys: `:conn`, `:sessions-between` (function
  `(conn from to) -> [{:date :startTime :endTime ..}]` of confirmed sessions, injected from the booking slice)."
  [{:keys [conn sessions-between]}]
  [{:name :calendar/create-blocks :method :post :path "/api/admin/calendar/blocks" :roles #{:owner :support}
    :schema {:body (s/closed-map [:fromDate s/Date] [:toDate {:optional true} [:maybe s/Date]]
                                 [:startTime {:optional true} [:maybe s/Time]] [:endTime {:optional true} [:maybe s/Time]]
                                 [:reason {:optional true} [:maybe (s/text 0 200)]])}
    :handler (fn [{{body :body} :params user :user}]
               (let [fields (core/validate-request body)
                     _ (when (seq fields) (err/fail! :validationFailed "Some details need attention." fields))
                     blocks (core/expand body (:name user) refs/new-id)
                     last-date (:date (last blocks))
                     sessions (sessions-between conn (:fromDate body) last-date)]
                 (store/insert-blocks! conn blocks)
                 (resp/created {:items blocks :affectedSessions (core/affected-sessions sessions blocks)})))}
   {:name :calendar/delete-block :method :delete :path "/api/admin/calendar/blocks/:id" :roles #{:owner :support}
    :schema {:path (s/closed-map [:id s/Id])}
    :handler (fn [{{{:keys [id]} :path} :params}]
               (when-not (store/delete-block! conn id) (err/fail! :notFound "That block does not exist."))
               (resp/no-content))}])
