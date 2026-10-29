(ns aurashots.features.catalog.core
  "Slice `catalog`, pure logic (architecture.md section 2.3): service package rules (price required to
  publish), record building, public views and basket quotes. No I/O."
  (:require [aurashots.shared.money :as money]
            [aurashots.shared.refs :as refs]))

(def reserved-slugs
  "Slugs that would collide with page routes (api-contract.md section 3.5)."
  #{"checkout" "received"})

(defn unique-slug
  "Returns a slug for `name` that is not reserved and not accepted by `taken?`; a numeric suffix is added if needed."
  [name taken?]
  (let [base (refs/slugify name)]
    (first (remove #(or (reserved-slugs %) (taken? %))
                   (cons base (map #(str base "-" %) (iterate inc 2)))))))

(defn priced?
  "True when `priceRwf` is a positive amount."
  [priceRwf]
  (boolean (and priceRwf (pos? priceRwf))))

(defn publish-errors
  "Returns a `fields` map when `body` asks to publish a service without a price (api-contract.md section 3.5),
  else an empty map."
  [{:keys [status priceRwf]}]
  (if (and (= "published" status) (not (priced? priceRwf)))
    {"priceRwf" "A price is required before a service can be published."}
    {}))

(def ^:private empty-contents
  {:editedPictures nil :videos nil :outfits nil :deliveryTime nil :locations nil})

(defn build-service
  "Builds the stored service map from validated admin `body` (string status becomes a keyword). `existing` is the
  current record or nil; the slug and id never change once assigned."
  [body {:keys [id slug existing]}]
  (-> (select-keys body [:name :shortDescription :description :priceRwf :sessionMinutes :conditions
                         :relatedProductIds :images :featured :sortOrder])
      (assoc :contents (merge empty-contents (:contents body))
             :status (keyword (:status body))
             :id (or (:id existing) id)
             :slug (or (:slug existing) slug))))

(defn publicly-visible?
  "True when the service is published and has a price, i.e. it may appear in the shop."
  [service]
  (and (= :published (:status service)) (priced? (:priceRwf service))))

(defn service-view
  "Builds the contract `Service` map for `service` with `main-image` (an `Image`)."
  [service main-image]
  (-> (select-keys service [:id :slug :name :shortDescription :priceRwf :sessionMinutes])
      (assoc :featured (boolean (:featured service))
             :mainImage main-image
             :contents (merge empty-contents (:contents service)))))

(defn quote-item
  "Builds one basket quote line for `service-id`. `service` is the stored record or nil; `main-image` an `Image`.
  Unavailable services report `reason` \"removed\", \"unpublished\" or \"noPrice\"."
  [service-id service main-image]
  (let [reason (cond (or (nil? service) (= :removed (:status service))) "removed"
                     (not= :published (:status service)) "unpublished"
                     (not (priced? (:priceRwf service))) "noPrice")]
    {:serviceId service-id :name (:name service) :priceRwf (:priceRwf service)
     :available (nil? reason) :reason reason :mainImage main-image}))

(defn quote-total
  "Total RwF of the available items in a quote."
  [items]
  (money/sum (map :priceRwf (filter :available items))))
