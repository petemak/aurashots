(ns aurashots.features.orders.api
  "Slice `orders`, public API (architecture.md sections 2.3 and 2.4): merchandise quotes, order submission, the
  customer status lookup, staff transitions and dashboard figures. Orchestrates I/O around the pure
  `orders.core`; every write is a single transaction."
  (:require [aurashots.features.documents.api :as documents]
            [aurashots.features.merch.api :as merch]
            [aurashots.features.notifications.api :as notify]
            [aurashots.features.orders.core :as core]
            [aurashots.features.orders.store :as store]
            [aurashots.features.site.api :as site]
            [aurashots.platform.crypto :as crypto]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]
            [clojure.string :as str]))

(def agreement-slug
  "Document slug of the merchandise order terms."
  "merchandise-order-terms")

(defn- token-hash
  "Keyed hash of a customer access token."
  [pepper token]
  (crypto/hmac-hex pepper (str "access:" token)))

(defn- active-area
  "The active delivery area `area-id`, or nil."
  [conn area-id]
  (let [a (when area-id (site/delivery-area conn area-id))]
    (when (:active a) a)))

(defn quote-merchandise
  "Builds the quote response for `lines` and optional `area-id` (read-only)."
  [conn lines area-id]
  (core/quote-body (merch/resolve-lines conn lines) lines (active-area conn area-id)))

(defn submit!
  "Handles a merchandise order (api-contract.md section 2.13). `deps` has `:conn :config`; `ctx` has `:now :ip
  :user-agent :done-tx`. Validates delivery and agreement, re-checks every item (`itemUnavailable` when not
  buyable), snapshots prices and the delivery fee, and commits order, acceptance record, emails and idempotency
  record in one transaction. Returns the 201 body."
  [{:keys [conn config]} input {:keys [now ip user-agent done-tx]}]
  (let [ver (documents/check-agreement! conn agreement-slug (:agreement input))
        delivery (:delivery input)
        _ (when (and (nil? (:areaId delivery)) (empty? (:areaText delivery)))
            (err/fail! :validationFailed "Choose a delivery area or describe where to deliver."
                       {"delivery.areaId" "choose an area or fill in areaText"}))
        area (when (:areaId delivery)
               (or (active-area conn (:areaId delivery))
                   (err/fail! :validationFailed "That delivery area is not available." {"delivery.areaId" "unknown area"})))
        results (merch/resolve-lines conn (:items input))
        bad-option (first (keep-indexed (fn [i r] (when (= :badOption (:reason r)) i)) results))
        _ (when bad-option (err/fail! :validationFailed "Choose one option from each group."
                                      {(str "items." bad-option ".optionIds") "invalid selection"}))
        unavailable (remove :ok results)
        _ (when (seq unavailable)
            (err/fail! :itemUnavailable (str "No longer available: "
                                             (str/join ", " (map #(or (:name %) "an item") unavailable)) ".")))
        token (crypto/random-token 16)
        id (refs/new-id)
        reference (or (first (remove #(store/reference-exists? conn %) (repeatedly 10 #(refs/reference :merchandise (crypto/secure-random)))))
                      (throw (ex-info "Could not generate a reference" {})))
        order (core/build-order input (map :line results) area
                                {:id id :reference reference :token-hash (token-hash (:token-pepper config) token) :now now})
        {:keys [record tx]} (documents/acceptance ver {:subject-type "merchandiseOrder" :subject-id id
                                                       :subject-reference reference :customer (:customer input)
                                                       :now now :ip ip :user-agent user-agent})
        order (assoc order :acceptanceId (:id record))
        body (core/public-view order (documents/acceptance-summary record) token)
        vars (core/email-vars order {:agreementTitle "Merchandise Order Terms" :agreementVersion (:version ver)
                                     :adminUrl (str (:public-base-url config) "/admin/orders/" id)})
        {mtx :tx} (notify/queue {:conn conn :config config :now now}
                                [{:template :orderReceived :to (get-in order [:customer :email]) :vars vars :subject-id id}
                                 {:template :orderAlert :to :owner :vars vars :subject-id id}])]
    (store/insert! conn order (concat tx mtx (when done-tx (done-tx 201 body))))
    body))

(defn public-by-reference
  "Returns the customer-facing order for `reference` and raw `token`, or nil when unknown or the token is wrong."
  [{:keys [conn config]} reference token]
  (when-let [o (store/get-by-reference conn reference)]
    (when (crypto/constant-time= (:tokenHash o) (token-hash (:token-pepper config) token))
      (core/public-view o (some-> (first (documents/acceptances-for conn reference)) documents/acceptance-summary) token))))

(defn- admin-view
  "Admin detail body for order `o`."
  [conn o]
  (core/admin-view o {:messages (notify/views-for conn (:id o))
                      :acceptance (first (documents/acceptances-for conn (:reference o)))
                      :document-url #(str "/api/documents/" (:documentSlug %) "?version=" (:version %))}))

(defn admin-detail
  "Admin detail view of order `id`, or throws `notFound`."
  [conn id]
  (admin-view conn (or (store/get-by-id conn id) (err/fail! :notFound "That order does not exist."))))

(defn admin-list
  "Admin list rows, optionally filtered by `status` (string)."
  [conn status]
  (mapv core/list-item (store/list-all conn (some-> status keyword))))

(defn- transition!
  "Atomically applies `f` `(fn [order] -> {:order o' :changed? bool :messages [specs]})`, saving and queueing
  messages when changed. Returns the admin view."
  [{:keys [conn config]} id now f]
  (store/in-tx
   conn
   (fn [c]
     (let [o (or (store/get-by-id c id) (err/fail! :notFound "That order does not exist."))
           {:keys [order changed? messages]} (f o)]
       (when changed?
         (store/save! c order)
         (when (seq messages)
           (store/transact! c (:tx (notify/queue {:conn c :config config :now now} messages)))))
       order)))
  (admin-view conn (store/get-by-id conn id)))

(defn- mail
  "Customer email spec for order `o`."
  [o template extra]
  [{:template template :to (get-in o [:customer :email]) :subject-id (:id o) :vars (core/email-vars o extra)}])

(defn approve!
  "Approves order `id` with `{:deliveryFeeRwf :noteToCustomer}`; emails the customer. Returns the admin view."
  [deps id body by now]
  (transition! deps id now
               (fn [o] (let [r (core/approve o body by now)]
                         (assoc r :messages (when (:changed? r) (mail (:order r) :orderApproved {:noteToCustomer (or (:noteToCustomer body) "")})))))))

(defn reject!
  "Rejects order `id` with `reason`; emails the customer."
  [deps id reason by now]
  (transition! deps id now
               (fn [o] (let [r (core/reject o reason by now)]
                         (assoc r :messages (when (:changed? r) (mail (:order r) :orderRejected {:reason reason})))))))

(defn mark-paid!
  "Records payment on order `id`."
  [deps id payment by now]
  (transition! deps id now (fn [o] (core/mark-paid o payment by now))))

(defn mark-delivered!
  "Marks order `id` delivered (recording payment when given and needed); emails the customer."
  [deps id payment by now]
  (transition! deps id now
               (fn [o] (let [r (core/mark-delivered o payment by now)]
                         (assoc r :messages (when (:changed? r) (mail (:order r) :orderDelivered {})))))))

(defn add-note!
  "Adds an internal note to order `id`; returns the note."
  [{:keys [conn]} id text by now]
  (store/in-tx conn (fn [c]
                      (let [o (or (store/get-by-id c id) (err/fail! :notFound "That order does not exist."))
                            {:keys [order note]} (core/add-note o text by (refs/new-id) now)]
                        (store/save! c order)
                        note))))

(defn resend!
  "Requeues message `message-id` of order `id`."
  [{:keys [conn]} id message-id now]
  (when-not (store/get-by-id conn id) (err/fail! :notFound "That order does not exist."))
  (notify/resend! conn id message-id now))

(defn pending-count
  "Number of orders waiting for a decision."
  [conn]
  (store/count-by-status conn :pending))
