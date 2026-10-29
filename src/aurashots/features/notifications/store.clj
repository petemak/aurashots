(ns aurashots.features.notifications.store
  "Slice `notifications`, data access (architecture.md section 3.2): `message` entities with indexed
  `:message/id :status :nextAttemptAt :subjectId :createdAt` and the record in `:message/data`."
  (:require [aurashots.shared.time :as t]
            [datalevin.core :as d]))

(defn message-tx
  "Transaction data that stores message record `m`."
  [m]
  [{:message/id (:id m) :message/status (:status m)
    :message/nextAttemptAt (t/epoch-ms (:nextAttemptAt m))
    :message/subjectId (:subjectId m) :message/createdAt (t/epoch-ms (:createdAt m))
    :message/data m}])

(defn save!
  "Stores or updates message record `m`. Side effect: one transaction."
  [conn m]
  (d/transact! conn (message-tx m))
  m)

(defn get-message
  "Returns the message record with `id`, or nil."
  [conn id]
  (:message/data (d/pull (d/db conn) [:message/data] [:message/id id])))

(defn for-subject
  "Returns message records belonging to `subject-id` (a booking, order, enquiry or user id), oldest first."
  [conn subject-id]
  (->> (d/q '[:find [(pull ?e [:message/data]) ...] :in $ ?s :where [?e :message/subjectId ?s]] (d/db conn) subject-id)
       (map :message/data) (sort-by :createdAt) vec))

(defn due
  "Returns up to `limit` queued message records whose next attempt time is at or before epoch-ms `now`."
  [conn now limit]
  (->> (d/q '[:find [(pull ?e [:message/data]) ...] :in $ ?now
              :where [?e :message/status :queued] [?e :message/nextAttemptAt ?t] [(<= ?t ?now)]]
            (d/db conn) now)
       (map :message/data) (sort-by :createdAt) (take limit) vec))

(defn count-with-status
  "Number of messages with status `status` (a keyword)."
  [conn status]
  (or (d/q '[:find (count ?e) . :in $ ?s :where [?e :message/status ?s]] (d/db conn) status) 0))
