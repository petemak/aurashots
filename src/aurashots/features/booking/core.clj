(ns aurashots.features.booking.core
  "Slice `booking`, pure logic (architecture.md section 2.3): builds booking requests, enforces the state machine of
  api-contract.md section 4, and shapes public and admin views. Every function takes data and returns data; the
  only side effect is throwing a domain error (`aurashots.shared.errors`) for illegal transitions. Time, ids and
  tokens are passed in."
  (:require [aurashots.shared.errors :as err]
            [aurashots.shared.money :as money]
            [aurashots.shared.time :as t]
            [aurashots.shared.whatsapp :as whatsapp]
            [clojure.string :as str]))

(defn history-entry
  "Builds an append-only history entry `{:at :byName :action :detail}`."
  [now by-name action detail]
  {:at now :byName by-name :action action :detail detail})

(defn build-request
  "Builds a new pending booking record. `input` has `:customer :contactPreference :preferredSlot :notes`; `services`
  are the stored service records; `ctx` has `:id :reference :token-hash :now :duration-minutes`. Prices and names are
  snapshotted so later catalogue edits never change the request."
  [input services {:keys [id reference token-hash now duration-minutes]}]
  (let [lines (mapv (fn [s] {:serviceId (:id s) :name (:name s) :priceRwf (:priceRwf s)}) services)]
    {:id id :reference reference :status :pending :createdAt now
     :customer (select-keys (:customer input) [:name :phone :email :address])
     :contactPreference (:contactPreference input)
     :services lines :totalRwf (money/sum (map :priceRwf lines))
     :notes (:notes input)
     :preferredSlot (select-keys (:preferredSlot input) [:date :startTime])
     :confirmedSlot nil :durationMinutes duration-minutes
     :customerNote nil
     :payment {:status "unpaid" :method nil :amountRwf nil :paidAt nil}
     :acceptanceId nil
     :internalNotes []
     :history [(history-entry now "Customer" "submitted" "Session request submitted")]
     :tokenHash token-hash}))

(defn end-time
  "End time `HH:mm` of a session starting at `start-time` and lasting `minutes`."
  [start-time minutes]
  (t/minutes->time (min 1440 (+ (t/parse-time start-time) minutes))))

(defn- append-history
  "Adds a history entry to booking `b`."
  [b now by action detail]
  (update b :history conj (history-entry now by action detail)))

(defn confirm
  "Applies `confirm` to booking `b` for `slot` `{:date :startTime}` with optional `note` by user name `by`.
  Returns `{:booking b' :changed? true :email :bookingConfirmed|:bookingTimeChanged}` (the email template to send), or `{:booking b :changed? false}` when the
  request is already confirmed for exactly that slot. `forced-over` (nil, :blocked or :alreadyConfirmed) is recorded
  in history when staff overrode a clash. Throws `conflict` from any other state, or when confirmed for a different slot."
  [b slot note by now forced-over]
  (case (:status b)
    :pending
    (let [same? (= (select-keys slot [:date :startTime]) (:preferredSlot b))]
      {:booking (-> b
                    (assoc :status :confirmed :confirmedSlot (select-keys slot [:date :startTime]) :customerNote note)
                    (append-history now by "confirmed"
                                    (str (:date slot) " " (:startTime slot)
                                         (when forced-over (str " (override: " (name forced-over) ")")))))
       :changed? true :email (if same? :bookingConfirmed :bookingTimeChanged)})
    :confirmed
    (if (= (select-keys slot [:date :startTime]) (:confirmedSlot b))
      {:booking b :changed? false}
      (err/fail! :conflict "This request is already confirmed for another time. Use change date or time instead."))
    (err/fail! :conflict "This request can no longer be confirmed.")))

(defn reschedule
  "Moves a confirmed booking to `slot`. Returns `{:booking b' :changed? true}`, or unchanged when `slot` equals the
  current confirmed slot. Throws `conflict` unless the booking is confirmed."
  [b slot note by now forced-over]
  (if (not= :confirmed (:status b))
    (err/fail! :conflict "Only a confirmed session can be moved.")
    (if (= (select-keys slot [:date :startTime]) (:confirmedSlot b))
      {:booking b :changed? false}
      {:booking (-> b
                    (assoc :confirmedSlot (select-keys slot [:date :startTime]) :customerNote note)
                    (append-history now by "rescheduled"
                                    (str (get-in b [:confirmedSlot :date]) " " (get-in b [:confirmedSlot :startTime])
                                         " to " (:date slot) " " (:startTime slot)
                                         (when forced-over (str " (override: " (name forced-over) ")")))))
       :changed? true})))

(defn decline
  "Declines a pending booking with `reason`. Repeating with the same reason on a declined request changes nothing.
  Throws `conflict` for any other state or a different reason."
  [b reason by now]
  (case (:status b)
    :pending {:booking (-> b (assoc :status :declined :customerNote reason) (append-history now by "declined" reason))
              :changed? true}
    :declined (if (= reason (:customerNote b))
                {:booking b :changed? false}
                (err/fail! :conflict "This request was already declined with a different reason."))
    (err/fail! :conflict "This request can no longer be declined.")))

(defn mark-paid
  "Records payment `{:method :amountRwf}` on a confirmed or completed booking. Repeating identical values changes
  nothing; different values on a paid booking throw `conflict`, as does any other status."
  [b {:keys [method amountRwf]} by now]
  (if-not (#{:confirmed :completed} (:status b))
    (err/fail! :conflict "Payment can only be recorded for a confirmed or completed session.")
    (let [p (:payment b)]
      (cond
        (and (= "paid" (:status p)) (= method (:method p)) (= amountRwf (:amountRwf p))) {:booking b :changed? false}
        (= "paid" (:status p)) (err/fail! :conflict "A different payment is already recorded.")
        :else {:booking (-> b
                            (assoc :payment {:status "paid" :method method :amountRwf amountRwf :paidAt now})
                            (append-history now by "paid" (str method ", " amountRwf " RwF")))
               :changed? true}))))

(defn complete
  "Marks a confirmed booking completed; completed stays completed. Throws `conflict` otherwise."
  [b by now]
  (case (:status b)
    :confirmed {:booking (-> b (assoc :status :completed) (append-history now by "completed" "Session completed"))
                :changed? true}
    :completed {:booking b :changed? false}
    (err/fail! :conflict "Only a confirmed session can be completed.")))

(defn add-note
  "Appends an internal note. `note-id` is a fresh id. Returns `{:booking b' :note note}`."
  [b text by note-id now]
  (let [note {:id note-id :text text :authorName by :createdAt now}]
    {:booking (-> b (update :internalNotes conj note) (append-history now by "note added" nil))
     :note note}))

(defn slot-conflict-label
  "Returns the `slotConflict` value for the admin detail: only meaningful while pending."
  [b conflict]
  (when (= :pending (:status b)) (some-> conflict name)))

(defn- service-lines
  "Multi-line text listing services and prices, for emails."
  [b]
  (str/join "\n" (map #(str "- " (:name %) " (" (:priceRwf %) " RwF)") (:services b))))

(defn email-vars
  "Template variables shared by the booking emails. `extra` is merged over them."
  [b extra]
  (merge {:customerName (get-in b [:customer :name]) :customerPhone (get-in b [:customer :phone])
          :customerEmail (get-in b [:customer :email]) :reference (:reference b)
          :services (service-lines b) :totalRwf (:totalRwf b)
          :contactPreference (:contactPreference b)
          :requestedDate (get-in b [:preferredSlot :date]) :requestedTime (get-in b [:preferredSlot :startTime])
          :notes (or (:notes b) "")}
         extra))

(defn public-view
  "Builds the customer-facing booking body (api-contract.md sections 2.11 and 2.12). `acceptance` is the
  `{slug version acceptedAt}` summary, `token` the raw access token to echo."
  [b acceptance token]
  (cond-> {:id (:id b) :reference (:reference b) :accessToken token :status (name (:status b))
           :createdAt (:createdAt b)
           :services (:services b) :totalRwf (:totalRwf b)
           :preferredSlot (:preferredSlot b) :contactPreference (:contactPreference b)
           :durationMinutes (:durationMinutes b) :agreementAcceptance acceptance}
    (#{:confirmed :completed} (:status b)) (assoc :confirmedSlot (:confirmedSlot b))
    (#{:confirmed :declined} (:status b)) (assoc :customerNote (:customerNote b))))

(defn list-item
  "One row of the admin list."
  [b]
  {:id (:id b) :reference (:reference b) :status (name (:status b))
   :customerName (get-in b [:customer :name]) :serviceNames (mapv :name (:services b))
   :preferredSlot (:preferredSlot b) :confirmedSlot (:confirmedSlot b) :createdAt (:createdAt b)})

(defn admin-view
  "Builds the admin detail body. `extra` supplies `:slotConflict`, `:messages`, `:acceptance` (full record) and
  `:document-url-fn` (version -> url)."
  [b {:keys [slotConflict messages acceptance document-url]}]
  {:id (:id b) :reference (:reference b) :status (name (:status b)) :createdAt (:createdAt b)
   :customer (:customer b) :contactPreference (:contactPreference b)
   :whatsappLink (whatsapp/link (get-in b [:customer :phone])
                                (str "Hello " (get-in b [:customer :name]) ", this is Aura Shots about your session request "
                                     (:reference b) "."))
   :services (:services b) :totalRwf (:totalRwf b) :notes (:notes b)
   :preferredSlot (:preferredSlot b) :confirmedSlot (:confirmedSlot b) :durationMinutes (:durationMinutes b)
   :slotConflict slotConflict :payment (:payment b)
   :agreementAcceptance (when acceptance
                          {:slug (:documentSlug acceptance) :version (:version acceptance)
                           :acceptedAt (:acceptedAt acceptance) :customerName (:customerName acceptance)
                           :customerEmail (:customerEmail acceptance) :bodyHash (:bodyHash acceptance)
                           :documentUrl (document-url acceptance)})
   :messages messages :internalNotes (:internalNotes b) :history (:history b)})
