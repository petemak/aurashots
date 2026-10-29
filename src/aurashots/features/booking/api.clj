(ns aurashots.features.booking.api
  "Slice `booking`, public API (architecture.md sections 2.3 and 2.4): availability, request submission, the
  customer status lookup, staff transitions with atomic slot re-checks, the admin calendar view and the dashboard
  figures. This namespace orchestrates I/O (store, other slices' APIs) around the pure `booking.core`; every
  function that writes does so in a single database transaction."
  (:require [aurashots.features.booking.core :as core]
            [aurashots.features.booking.store :as store]
            [aurashots.features.calendar.api :as calendar]
            [aurashots.features.catalog.api :as catalog]
            [aurashots.features.documents.api :as documents]
            [aurashots.features.notifications.api :as notify]
            [aurashots.features.site.api :as site]
            [aurashots.platform.crypto :as crypto]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]
            [aurashots.shared.slots :as slots]
            [aurashots.shared.time :as t]
            [clojure.string :as str]))

(def agreement-slug
  "The document slug of the session booking agreement."
  "session-booking-agreement")

(defn- session-data
  "Converts confirmed booking records to the `{:id :date :startTime :durationMinutes}` maps used by slot rules."
  [bookings]
  (mapv (fn [b] {:id (:id b) :date (get-in b [:confirmedSlot :date]) :startTime (get-in b [:confirmedSlot :startTime])
                 :durationMinutes (:durationMinutes b)})
        bookings))

(defn- slot-ctx
  "Builds the context map for `aurashots.shared.slots` for ISO dates `from` to `to` and `minutes` session length."
  [conn now from to minutes]
  {:now now :settings (site/settings conn) :blocks (calendar/blocks-between conn from to)
   :sessions (session-data (store/confirmed-between conn from to)) :session-minutes minutes})

(defn session-minutes-for
  "Session length for `service-ids` (api-contract.md section 2.7): the sum of the services' lengths, each falling back
  to the default from settings, or the default alone when none is known (60 when that is unset too)."
  [conn service-ids]
  (slots/session-length (catalog/session-minutes conn service-ids)
                        (get-in (site/settings conn) [:booking :defaultSessionMinutes])))

(defn availability
  "Computes the availability response. `q` has `:from :to` (ISO dates), optional `:date` and `:service-ids`.
  `now` is an Instant. Read-only."
  [conn {:keys [from to date service-ids]} now]
  (let [f (t/parse-date from) tt (t/parse-date to)
        span (count (t/date-range f tt))
        minutes (session-minutes-for conn service-ids)]
    (slots/availability
     (assoc (slot-ctx conn now from to minutes)
            :from f :to tt
            :with-slots? (cond (<= span 7) true date #{date} :else #{})))))

(defn- token-hash
  "Keyed hash of the customer access token."
  [pepper token]
  (crypto/hmac-hex pepper (str "access:" token)))

(defn- unique-reference
  "Generates a session reference that is not yet used."
  [conn]
  (or (first (remove #(store/reference-exists? conn %) (repeatedly 10 #(refs/reference :session (crypto/secure-random)))))
      (throw (ex-info "Could not generate a reference" {}))))

(defn- joined-names
  "Comma separated names for error messages."
  [names]
  (str/join ", " (map #(or % "an unknown service") names)))

(defn submit!
  "Handles a customer session request (api-contract.md section 2.11). `deps` has `:conn :config`; `input` is the
  validated body; `ctx` has `:now :ip :user-agent` and `:done-tx` (idempotency record builder `(status body) ->
  tx-data`, may be nil). Re-checks services and the slot, then commits booking, acceptance record, history, emails
  and idempotency record in one transaction. Returns the 201 response body. Throws `itemUnavailable`,
  `slotUnavailable`, `agreementVersionStale` or `validationFailed`."
  [{:keys [conn config]} input {:keys [now ip user-agent done-tx]}]
  (let [ver (documents/check-agreement! conn agreement-slug (:agreement input))
        ids (:serviceIds input)
        _ (when (not= (count ids) (count (set ids)))
            (err/fail! :validationFailed "Each service can only be chosen once." {:serviceIds "must be unique"}))
        {:keys [services unavailable]} (catalog/bookable conn ids)
        _ (when (seq unavailable)
            (err/fail! :itemUnavailable (str "No longer available: " (joined-names unavailable) ".")))
        minutes (session-minutes-for conn ids)
        slot (:preferredSlot input)
        date (t/parse-date (:date slot))
        _ (when-not (slots/slot-available? (slot-ctx conn now (:date slot) (:date slot) minutes) date (:startTime slot))
            (err/fail! :slotUnavailable "That time is no longer available. Please choose another."))
        token (crypto/random-token 16)
        pepper (:token-pepper config)
        id (refs/new-id)
        reference (unique-reference conn)
        booking (core/build-request input services {:id id :reference reference :token-hash (token-hash pepper token)
                                                    :now now :duration-minutes minutes})
        {:keys [record tx]} (documents/acceptance ver {:subject-type "bookingRequest" :subject-id id
                                                       :subject-reference reference :customer (:customer input)
                                                       :now now :ip ip :user-agent user-agent})
        booking (assoc booking :acceptanceId (:id record))
        body (core/public-view booking (documents/acceptance-summary record) token)
        vars (core/email-vars booking {:agreementTitle "Session Booking Agreement" :agreementVersion (:version ver)
                                       :adminUrl (str (:public-base-url config) "/admin/session-requests/" id)})
        {mtx :tx} (notify/queue {:conn conn :config config :now now}
                                [{:template :bookingReceived :to (get-in booking [:customer :email]) :vars vars :subject-id id}
                                 {:template :bookingAlert :to :owner :vars vars :subject-id id}])]
    (store/insert! conn booking (concat tx mtx (when done-tx (done-tx 201 body))))
    body))

(defn public-by-reference
  "Looks up a booking for the customer by `reference` and raw access `token`. Returns the public body or nil
  when the reference is unknown or the token is wrong (callers answer 404 without a hint)."
  [{:keys [conn config]} reference token]
  (when-let [b (store/get-by-reference conn reference)]
    (when (crypto/constant-time= (:tokenHash b) (token-hash (:token-pepper config) token))
      (let [acc (first (documents/acceptances-for conn reference))]
        (core/public-view b (some-> acc documents/acceptance-summary) token)))))

(defn- admin-view
  "Builds the admin detail body for booking `b`, reading messages, acceptance and slot conflict from `conn`."
  [conn b]
  (let [slot (:preferredSlot b)
        conflict (when (= :pending (:status b))
                   (let [d (:date slot)]
                     (slots/conflict d (t/parse-time (:startTime slot)) (:durationMinutes b)
                                     (calendar/blocks-between conn d d) (session-data (store/confirmed-between conn d d)))))
        acc (first (documents/acceptances-for conn (:reference b)))]
    (core/admin-view b {:slotConflict (core/slot-conflict-label b conflict)
                        :messages (notify/views-for conn (:id b))
                        :acceptance acc
                        :document-url #(str "/api/documents/" (:documentSlug %) "?version=" (:version %))})))

(defn admin-detail
  "Returns the admin detail view of booking `id`, or throws `notFound`."
  [conn id]
  (admin-view conn (or (store/get-by-id conn id) (err/fail! :notFound "That request does not exist."))))

(defn admin-list
  "Returns all booking records for the admin list, optionally filtered by `status` (string)."
  [conn status]
  (mapv core/list-item (store/list-all conn (some-> status keyword))))

(defn- clash
  "The slot clash (nil, :blocked or :alreadyConfirmed) of `slot` for booking `b` inside transaction connection `c`."
  [c b slot]
  (let [d (:date slot)]
    (slots/conflict d (t/parse-time (:startTime slot)) (:durationMinutes b)
                    (calendar/blocks-between c d d) (session-data (store/confirmed-between c d d)) (:id b))))

(defn- transition!
  "Runs a staff transition atomically. `f` is `(fn [c booking] -> {:booking b' :changed? bool :messages [specs]})` and
  may throw domain errors. Saves the booking and queues the messages inside the same transaction when changed.
  Returns the admin view of the resulting booking."
  [{:keys [conn config]} id now f]
  (store/in-tx
   conn
   (fn [c]
     (let [b (or (store/get-by-id c id) (err/fail! :notFound "That request does not exist."))
           {:keys [booking changed? messages]} (f c b)]
       (when changed?
         (store/save! c booking)
         (when (seq messages)
           (store/transact! c (:tx (notify/queue {:conn c :config config :now now} messages)))))
       booking)))
  (admin-view conn (store/get-by-id conn id)))

(defn- slot-messages
  "Customer email spec for a confirmed or moved session."
  [b template note]
  [{:template template :to (get-in b [:customer :email]) :subject-id (:id b)
    :vars (core/email-vars b {:date (get-in b [:confirmedSlot :date]) :startTime (get-in b [:confirmedSlot :startTime])
                              :noteToCustomer (or note "")})}])

(defn confirm!
  "Confirms booking `id` for `{:date :startTime :noteToCustomer :force}` by user name `by`. A clash with a block or
  another confirmed session throws `slotUnavailable` unless `:force` is true. Returns the admin view."
  [deps id {:keys [date startTime noteToCustomer force]} by now]
  (transition!
   deps id now
   (fn [c b]
     (let [slot {:date date :startTime startTime}
           repeat? (and (= :confirmed (:status b)) (= slot (:confirmedSlot b)))
           clash (when-not repeat? (clash c b slot))
           _ (when (and clash (not force)) (err/fail! :slotUnavailable "That time clashes with a block or another confirmed session."))
           r (core/confirm b slot noteToCustomer by now clash)]
       (assoc r :messages (when (:changed? r) (slot-messages (:booking r) (:email r) noteToCustomer)))))))

(defn reschedule!
  "Moves confirmed booking `id` to `{:date :startTime :noteToCustomer :force}`; see `confirm!` for clash rules."
  [deps id {:keys [date startTime noteToCustomer force]} by now]
  (transition!
   deps id now
   (fn [c b]
     (let [slot {:date date :startTime startTime}
           same? (and (= :confirmed (:status b)) (= slot (:confirmedSlot b)))
           clash (when (and (= :confirmed (:status b)) (not same?)) (clash c b slot))
           _ (when (and clash (not force)) (err/fail! :slotUnavailable "That time clashes with a block or another confirmed session."))
           r (core/reschedule b slot noteToCustomer by now clash)]
       (assoc r :messages (when (:changed? r) (slot-messages (:booking r) :bookingTimeChanged noteToCustomer)))))))

(defn decline!
  "Declines pending booking `id` with `reason`, emailing the customer. Returns the admin view."
  [deps id reason by now]
  (transition!
   deps id now
   (fn [_ b]
     (let [r (core/decline b reason by now)]
       (assoc r :messages (when (:changed? r)
                            [{:template :bookingDeclined :to (get-in b [:customer :email]) :subject-id id
                              :vars (core/email-vars b {:reason reason})}]))))))

(defn mark-paid!
  "Records payment `{:method :amountRwf}` on booking `id`. Returns the admin view."
  [deps id payment by now]
  (transition! deps id now (fn [_ b] (core/mark-paid b payment by now))))

(defn complete!
  "Marks booking `id` completed. Returns the admin view."
  [deps id by now]
  (transition! deps id now (fn [_ b] (core/complete b by now))))

(defn add-note!
  "Adds an internal note with `text` by user name `by` to booking `id`; returns the note."
  [{:keys [conn]} id text by now]
  (store/in-tx conn (fn [c]
                      (let [b (or (store/get-by-id c id) (err/fail! :notFound "That request does not exist."))
                            {:keys [booking note]} (core/add-note b text by (refs/new-id) now)]
                        (store/save! c booking)
                        note))))

(defn resend!
  "Requeues message `message-id` of booking `id`; returns the `Message` view."
  [{:keys [conn]} id message-id now]
  (when-not (store/get-by-id conn id) (err/fail! :notFound "That request does not exist."))
  (notify/resend! conn id message-id now))

(defn sessions-between
  "Confirmed sessions between ISO dates as `{:id :reference :date :startTime :endTime :customerName :status}`
  (used by the calendar view and injected into the calendar slice for block warnings)."
  [conn from to]
  (mapv (fn [b] {:id (:id b) :reference (:reference b)
                 :date (get-in b [:confirmedSlot :date]) :startTime (get-in b [:confirmedSlot :startTime])
                 :endTime (core/end-time (get-in b [:confirmedSlot :startTime]) (:durationMinutes b))
                 :customerName (get-in b [:customer :name]) :status "confirmed"})
        (store/confirmed-between conn from to)))

(defn calendar-view
  "The admin calendar body for ISO dates `from` to `to`: blocks, confirmed sessions and pending requests."
  [conn from to]
  {:blocks (mapv #(select-keys % [:id :date :startTime :endTime :reason :createdByName]) (calendar/blocks-between conn from to))
   :sessions (sessions-between conn from to)
   :pending (mapv (fn [b] {:id (:id b) :reference (:reference b) :date (get-in b [:preferredSlot :date])
                           :startTime (get-in b [:preferredSlot :startTime]) :customerName (get-in b [:customer :name])})
                  (store/pending-between conn from to))})

(defn dashboard-figures
  "Session figures for the dashboard: `{:pendingSessionRequests n :upcomingSessions [..]}` (next 10 confirmed
  sessions from today in the business zone)."
  [conn now]
  (let [today (str (t/today now))]
    {:pendingSessionRequests (store/count-by-status conn :pending)
     :upcomingSessions (->> (store/confirmed-between conn today "9999-12-31")
                            (take 10)
                            (mapv (fn [b] {:id (:id b) :reference (:reference b) :customerName (get-in b [:customer :name])
                                           :date (get-in b [:confirmedSlot :date]) :startTime (get-in b [:confirmedSlot :startTime])
                                           :services (mapv :name (:services b))})))}))
