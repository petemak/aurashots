(ns aurashots.features.orders.core
  "Slice `orders`, pure logic (architecture.md section 2.3): quotes, order building with price and delivery-fee
  snapshots, the state machine of api-contract.md section 4 (pending, approved, paid, delivered, rejected) and
  customer/admin views. No I/O; time, ids and tokens are passed in. Illegal transitions throw `conflict`."
  (:require [aurashots.shared.errors :as err]
            [aurashots.shared.money :as money]
            [aurashots.shared.whatsapp :as whatsapp]
            [clojure.string :as str]))

(def reason-labels
  "Contract strings for line unavailability reasons."
  {:removed "removed" :unpublished "unpublished" :soldOut "soldOut" :badOption "invalidOption"})

(defn quote-body
  "Builds the merchandise quote response (api-contract.md section 2.9) from `results` (as returned by
  `merch.api/resolve-lines`, in request order), the request `lines` and the delivery `area` (stored map or nil)."
  [results lines area]
  (let [oks (filter :ok results)
        subtotal (money/sum (map #(get-in % [:line :lineTotalRwf]) oks))
        fee (:feeRwf area)]
    {:items (mapv (fn [r l]
                    (if (:ok r)
                      (assoc (:line r) :available true :reason nil)
                      {:productId (:productId l) :name (:name r) :optionLabels [] :unitPriceRwf nil
                       :quantity (:quantity l) :lineTotalRwf nil :available false :reason (reason-labels (:reason r))}))
                  results lines)
     :subtotalRwf subtotal
     :deliveryFeeRwf fee
     :totalRwf (when fee (+ subtotal fee))
     :feeNote (when (nil? fee) "toBeConfirmed")}))

(defn history-entry
  "Builds an append-only history entry."
  [now by action detail]
  {:at now :byName by :action action :detail detail})

(defn build-order
  "Builds a pending order record. `input` is the validated body; `lines` the resolved snapshot lines; `area` the
  selected delivery area or nil; `ctx` has `:id :reference :token-hash :now`."
  [input lines area {:keys [id reference token-hash now]}]
  (let [delivery (:delivery input)
        subtotal (money/sum (map :lineTotalRwf lines))
        fee (:feeRwf area)]
    {:id id :reference reference :status :pending :createdAt now
     :customer (select-keys (:customer input) [:name :phone :email])
     :delivery {:areaId (:areaId delivery) :areaName (:name area) :areaText (:areaText delivery)
                :address (:address delivery) :landmark (:landmark delivery) :notes (:notes delivery)}
     :items (vec lines) :subtotalRwf subtotal :deliveryFeeRwf fee :totalRwf (when fee (+ subtotal fee))
     :payment {:status "unpaid" :method nil :amountRwf nil :paidAt nil}
     :customerNote nil :acceptanceId nil :internalNotes []
     :history [(history-entry now "Customer" "submitted" "Order submitted")]
     :tokenHash token-hash}))

(defn- add-history
  "Appends a history entry to order `o`."
  [o now by action detail]
  (update o :history conj (history-entry now by action detail)))

(defn approve
  "Approves a pending order with the delivery fee `fee` (the stored fee when nil) and optional `note`. Throws
  `validationFailed` when no fee is known, `conflict` for other states. Repeating with the resulting fee on an
  approved order changes nothing."
  [o {:keys [deliveryFeeRwf noteToCustomer]} by now]
  (let [fee (or deliveryFeeRwf (:deliveryFeeRwf o))]
    (case (:status o)
      :pending
      (do (when (nil? fee) (err/fail! :validationFailed "Enter the delivery fee before approving."
                                      {"deliveryFeeRwf" "required because the fee is still to be confirmed"}))
          {:order (-> o (assoc :status :approved :deliveryFeeRwf fee :totalRwf (+ (:subtotalRwf o) fee) :customerNote noteToCustomer)
                      (add-history now by "approved" (str "Delivery fee " fee " RwF")))
           :changed? true})
      :approved (if (= fee (:deliveryFeeRwf o))
                  {:order o :changed? false}
                  (err/fail! :conflict "This order was already approved with a different delivery fee."))
      (err/fail! :conflict "This order can no longer be approved."))))

(defn reject
  "Rejects a pending order with `reason`; repeating the same reason is a no-op; otherwise `conflict`."
  [o reason by now]
  (case (:status o)
    :pending {:order (-> o (assoc :status :rejected :customerNote reason) (add-history now by "rejected" reason)) :changed? true}
    :rejected (if (= reason (:customerNote o))
                {:order o :changed? false}
                (err/fail! :conflict "This order was already rejected with a different reason."))
    (err/fail! :conflict "This order can no longer be rejected.")))

(defn- paid-order
  "Returns `o` with payment recorded."
  [o {:keys [method amountRwf]} by now]
  (-> o (assoc :payment {:status "paid" :method method :amountRwf amountRwf :paidAt now})
      (add-history now by "paid" (str method ", " amountRwf " RwF"))))

(defn mark-paid
  "Records payment on an approved order and moves it to paid. Repeating identical values on a paid order is a no-op;
  different values or other states throw `conflict`."
  [o {:keys [method amountRwf] :as payment} by now]
  (case (:status o)
    :approved {:order (assoc (paid-order o payment by now) :status :paid) :changed? true}
    :paid (if (and (= method (get-in o [:payment :method])) (= amountRwf (get-in o [:payment :amountRwf])))
            {:order o :changed? false}
            (err/fail! :conflict "A different payment is already recorded."))
    (err/fail! :conflict "Payment can only be recorded for an approved order.")))

(defn mark-delivered
  "Marks an order delivered. From `paid` no body is needed; from `approved` (pay on delivery) `method` and
  `amountRwf` must be given and are recorded in the same step. Delivered stays delivered. Others throw `conflict`."
  [o {:keys [method amountRwf] :as payment} by now]
  (case (:status o)
    :paid {:order (-> o (assoc :status :delivered) (add-history now by "delivered" nil)) :changed? true}
    :approved (do (when-not (and method amountRwf)
                    (err/fail! :validationFailed "Record the payment when delivering an unpaid order."
                               {"method" "required" "amountRwf" "required"}))
                  {:order (-> (paid-order o payment by now) (assoc :status :delivered) (add-history now by "delivered" nil))
                   :changed? true})
    :delivered {:order o :changed? false}
    (err/fail! :conflict "Only an approved or paid order can be delivered.")))

(defn add-note
  "Appends an internal note; returns `{:order o' :note n}`."
  [o text by note-id now]
  (let [note {:id note-id :text text :authorName by :createdAt now}]
    {:order (-> o (update :internalNotes conj note) (add-history now by "note added" nil)) :note note}))

(defn- item-lines
  "Multi-line text listing order items for emails."
  [o]
  (str/join "\n" (map #(str "- " (:quantity %) " x " (:name %)
                            (when (seq (:optionLabels %)) (str " (" (str/join ", " (:optionLabels %)) ")"))
                            ": " (:lineTotalRwf %) " RwF")
                      (:items o))))

(defn delivery-text
  "One-line delivery destination for emails."
  [{:keys [delivery]}]
  (str/join ", " (remove str/blank? [(:address delivery) (:landmark delivery) (or (:areaName delivery) (:areaText delivery))])))

(defn email-vars
  "Template variables for order emails; `extra` is merged over them."
  [o extra]
  (merge {:customerName (get-in o [:customer :name]) :customerPhone (get-in o [:customer :phone])
          :customerEmail (get-in o [:customer :email]) :reference (:reference o) :items (item-lines o)
          :subtotalRwf (:subtotalRwf o) :deliveryFeeRwf (:deliveryFeeRwf o) :totalRwf (:totalRwf o)
          :deliveryAddress (delivery-text o)}
         extra))

(defn public-view
  "Builds the customer-facing order body (api-contract.md sections 2.13 and 2.14)."
  [o acceptance token]
  (cond-> {:id (:id o) :reference (:reference o) :accessToken token :status (name (:status o))
           :createdAt (:createdAt o)
           :items (:items o) :subtotalRwf (:subtotalRwf o) :deliveryFeeRwf (:deliveryFeeRwf o) :totalRwf (:totalRwf o)
           :delivery (:delivery o) :agreementAcceptance acceptance}
    (:customerNote o) (assoc :customerNote (:customerNote o))))

(defn list-item
  "One row of the admin order list."
  [o]
  {:id (:id o) :reference (:reference o) :status (name (:status o)) :customerName (get-in o [:customer :name])
   :itemCount (reduce + 0 (map :quantity (:items o))) :totalRwf (:totalRwf o) :createdAt (:createdAt o)})

(defn admin-view
  "Builds the admin order detail; `extra` has `:messages`, `:acceptance` (record) and `:document-url`."
  [o {:keys [messages acceptance document-url]}]
  {:id (:id o) :reference (:reference o) :status (name (:status o)) :createdAt (:createdAt o)
   :customer (:customer o)
   :whatsappLink (whatsapp/link (get-in o [:customer :phone])
                                (str "Hello " (get-in o [:customer :name]) ", this is Aura Shots about your order " (:reference o) "."))
   :delivery (:delivery o) :items (:items o) :subtotalRwf (:subtotalRwf o) :deliveryFeeRwf (:deliveryFeeRwf o)
   :totalRwf (:totalRwf o) :customerNote (:customerNote o) :payment (:payment o)
   :agreementAcceptance (when acceptance
                          {:slug (:documentSlug acceptance) :version (:version acceptance) :acceptedAt (:acceptedAt acceptance)
                           :customerName (:customerName acceptance) :customerEmail (:customerEmail acceptance)
                           :bodyHash (:bodyHash acceptance) :documentUrl (document-url acceptance)})
   :messages messages :internalNotes (:internalNotes o) :history (:history o)})
