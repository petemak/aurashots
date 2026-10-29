(ns aurashots.features.site.core
  "Slice `site`, pure logic (architecture.md section 2.3): shapes the settings singleton into the public
  `site-info` view and the admin settings view, and applies an admin update. No I/O."
  (:require [aurashots.shared.refs :as refs]
            [aurashots.shared.time :as t]))

(def slot-minutes
  "Booking grid in minutes; fixed by the contract (api-contract.md section 2.1)."
  30)

(def placeholder-email
  "Shown as the public email while no real address is configured (design-spec: owner email shown as OWNER_EMAIL)."
  "OWNER_EMAIL")

(defn public-info
  "Builds the `GET /api/site-info` body from `settings` and the configured `owner-email` (used when the
  settings hold no email). Only active delivery areas are listed."
  [settings owner-email]
  {:name "Aura Shots"
   :tagline "Not just photos…it's an aura"
   :address (:address settings)
   :phone (:phone settings)
   :whatsapp (:whatsapp settings)
   :email (or (:email settings) owner-email placeholder-email)
   :social (merge {:instagram nil :tiktok nil :facebook nil} (:social settings))
   :openingHours (:openingHours settings)
   :timeZone "Africa/Kigali"
   :booking (assoc (:booking settings) :slotMinutes slot-minutes)
   :deliveryAreas (->> (:deliveryAreas settings) (filter :active) (mapv #(select-keys % [:id :name :feeRwf])))
   :portfolioCategories (:portfolioCategories settings)})

(defn admin-view
  "Builds the `GET /api/admin/settings` body: the public shape with every delivery area (including inactive
  ones, with their `active` flag)."
  [settings owner-email]
  (assoc (public-info settings owner-email) :deliveryAreas (:deliveryAreas settings)))

(defn validate-update
  "Checks cross-field rules of a settings update body; returns a map of field to message (empty when valid)."
  [body]
  (merge
   (into {} (keep-indexed
             (fn [i {:keys [open close]}]
               (when (>= (or (t/parse-time open) 0) (or (t/parse-time close) 0))
                 [(str "openingHours." i ".close") "must be later than the opening time"]))
             (:openingHours body)))
   (let [days (mapcat :days (:openingHours body))]
     (when (not= (count days) (count (set days)))
       {"openingHours" "a day may appear only once"}))))

(defn apply-update
  "Returns the new settings map from the validated update `body`. New delivery areas and categories (no `:id`)
  receive generated ids (categories use a slug of the name)."
  [body]
  (-> (select-keys body [:phone :whatsapp :email :address :social :openingHours :booking])
      (update :booking dissoc :slotMinutes)
      (update :email #(when-not (= placeholder-email %) %))
      (assoc :deliveryAreas (mapv (fn [a] (assoc a :id (or (:id a) (refs/new-id)))) (:deliveryAreas body)))
      (assoc :portfolioCategories (mapv (fn [c] (assoc c :id (or (:id c) (refs/slugify (:name c))))) (:portfolioCategories body)))))

(defn removed-category-ids
  "Ids of categories present in `old-settings` but absent from `new-settings`."
  [old-settings new-settings]
  (let [keep-ids (set (map :id (:portfolioCategories new-settings)))]
    (->> (:portfolioCategories old-settings) (map :id) (remove keep-ids) vec)))
