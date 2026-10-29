(ns aurashots.features.notifications.api
  "Slice `notifications`, public API for other slices and the outbox worker (architecture.md section 2.4):
  builds queued messages (as transaction data, so they commit with the booking, order or enquiry), lists and
  resends them, and delivers due messages through the injected mailer function."
  (:require [aurashots.features.notifications.core :as core]
            [aurashots.features.notifications.store :as store]
            [aurashots.features.site.api :as site]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]
            [aurashots.shared.time :as t]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

(defn- template-text
  "Reads `resources/email/<template>.txt`; throws when the template does not exist."
  [template]
  (if-let [r (io/resource (str "email/" (name template) ".txt"))]
    (slurp r)
    (throw (ex-info "Unknown email template" {:template template}))))

(defn queue
  "Builds queued messages. `deps` has `:conn`, `:config` (for the fallback owner address) and `:now` (an Instant).
  Each entry of `specs` is `{:template kw :to address|:owner :vars map :subject-id id}`; `:owner` resolves to the
  contact address (settings email, else OWNER_EMAIL) and the entry is skipped when none is configured.
  Returns `{:records [message-records] :tx tx-data}`; nothing is written until the caller commits `tx`."
  [{:keys [conn config now]} specs]
  (let [owner (site/contact-email conn (:owner-email config))
        records (vec (keep (fn [{:keys [template to vars subject-id]}]
                             (let [addr (if (= :owner to) owner to)]
                               (when addr
                                 (core/new-message (merge (core/render (template-text template) vars)
                                                          {:id (refs/new-id) :to addr :template template :subject-id subject-id})
                                                   now))))
                           specs))]
    {:records records :tx (vec (mapcat store/message-tx records))}))

(defn views-for
  "Returns the contract `Message` maps for `subject-id`, oldest first."
  [conn subject-id]
  (mapv core/view (store/for-subject conn subject-id)))

(defn resend!
  "Requeues message `message-id`, which must belong to `subject-id`, for immediate delivery. Returns its `Message`
  view. Throws `notFound` otherwise. Side effect: one transaction."
  [conn subject-id message-id now]
  (let [m (store/get-message conn message-id)]
    (when-not (and m (= subject-id (:subjectId m))) (err/fail! :notFound "That message does not exist."))
    (core/view (store/save! conn (core/requeue m now)))))

(defn deliver-due!
  "Sends every due queued message using `send-fn` (a function of `{:from :to :subject :text :html}` that throws on
  failure) at `now` (an Instant), recording success, retry schedule or final failure. `from` is the sender address.
  Returns `{:sent n :failed n}`. Side effects: SMTP I/O, database writes, logging (never the message text)."
  [conn send-fn from now]
  (reduce (fn [acc m]
            (let [err-msg (try (send-fn {:from from :to (:to m) :subject (:subject m) :text (:text m) :html (:html m)})
                               nil
                               (catch Exception e (str (or (ex-message e) (.getName (class e))))))
                  m' (core/after-attempt m (some-> err-msg (subs 0 (min 300 (count err-msg)))) now)]
              (store/save! conn m')
              (if err-msg
                (do (log/warn "Email delivery failed" {:messageId (:id m) :template (:template m) :attempt (:attempts m') :status (name (:status m'))})
                    (update acc :failed inc))
                (update acc :sent inc))))
          {:sent 0 :failed 0}
          (store/due conn (t/epoch-ms now) 20)))

(defn queued-count
  "Number of messages waiting to be sent."
  [conn]
  (store/count-with-status conn :queued))
