(ns aurashots.features.enquiries.api
  "Slice `enquiries`, public API (architecture.md sections 2.3 and 2.4): submit a contact-form enquiry, list and
  answer enquiries, and the unanswered count for the dashboard."
  (:require [aurashots.features.enquiries.core :as core]
            [aurashots.features.enquiries.store :as store]
            [aurashots.features.notifications.api :as notify]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]))

(defn submit!
  "Stores an enquiry from validated `body`, queues the owner alert and the customer acknowledgement, and records
  the idempotency result, all in one transaction. `deps` has `:conn :config`; `ctx` has `:now` and `:done-tx`
  (may be nil). Returns the 201 body `{:id :receivedAt}`."
  [{:keys [conn config]} body {:keys [now done-tx]}]
  (let [id (refs/new-id)
        e (core/build-enquiry body id now)
        out {:id id :receivedAt now}
        vars {:name (:name e) :email (:email e) :phone (or (:phone e) "no phone given") :message (:message e)
              :adminUrl (str (:public-base-url config) "/admin/messages")}
        {mtx :tx} (notify/queue {:conn conn :config config :now now}
                                [{:template :enquiryAlert :to :owner :vars vars :subject-id id}
                                 {:template :enquiryAck :to (:email e) :vars vars :subject-id id}])]
    (store/insert! conn e (concat mtx (when done-tx (done-tx 201 out))))
    out))

(defn admin-list
  "Admin list items, optionally only with `status` (string `new` or `answered`)."
  [conn status]
  (mapv core/admin-view (store/list-all conn (some-> status keyword))))

(defn answer!
  "Marks enquiry `id` answered (idempotent) and returns its admin view. Throws `notFound`."
  [conn id]
  (let [e (or (store/get-by-id conn id) (err/fail! :notFound "That enquiry does not exist."))]
    (core/admin-view (if (= :answered (:status e)) e (store/save! conn (core/answer e))))))

(defn unanswered-count
  "Number of enquiries still waiting for an answer."
  [conn]
  (store/count-by-status conn :new))
