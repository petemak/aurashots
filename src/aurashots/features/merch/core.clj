(ns aurashots.features.merch.core
  "Slice `merch`, pure logic (architecture.md section 2.3): builds product records from admin input,
  derives availability, resolves order lines against options, and shapes public and admin views. No I/O.
  Stock is never counted: only the staff-set `inStock` flags matter (business-context.md)."
  (:require [aurashots.shared.money :as money]
            [aurashots.shared.refs :as refs]
            [clojure.string :as str]))

(def reserved-slugs
  "Slugs that would collide with page routes under /shop (api-contract.md section 3.6)."
  #{"checkout" "received"})

(defn unique-slug
  "Returns a slug for `name` that is neither reserved nor accepted by `taken?` (a predicate on slugs); a numeric
  suffix is appended when needed."
  [name taken?]
  (let [base (refs/slugify name)]
    (first (remove #(or (reserved-slugs %) (taken? %))
                   (cons base (map #(str base "-" %) (iterate inc 2)))))))

(defn- ensure-option-ids
  "Gives every option without an id a fresh one."
  [groups]
  (mapv (fn [g] (update g :options (fn [os] (mapv #(update % :id (fn [i] (or i (refs/new-id)))) os)))) groups))

(defn build-product
  "Builds the stored product map from validated admin `body`. `existing` is the current record or nil.
  `id`, `slug` and `now` (an Instant) are supplied by the caller for new products. `inStock` (the product-level
  flag) defaults to true and is preserved on update."
  [body {:keys [id slug now existing]}]
  (-> (select-keys body [:name :category :description :details :priceRwf :isNew :featured :status :images])
      (assoc :optionGroups (ensure-option-ids (:optionGroups body))
             :id (or (:id existing) id)
             :slug (or (:slug existing) slug)
             :createdAt (or (:createdAt existing) now)
             :inStock (if existing (:inStock existing true) true))))

(defn availability
  "Returns \"inStock\" or \"soldOut\". A product is sold out when its product-level flag is off or any option
  group has no option in stock."
  [{:keys [inStock optionGroups]}]
  (if (or (false? inStock)
          (some (fn [g] (and (seq (:options g)) (not-any? :inStock (:options g)))) optionGroups))
    "soldOut"
    "inStock"))

(defn summary
  "Builds the contract `ProductSummary` for `product`, with `main-image` (an `Image`)."
  [product main-image]
  {:id (:id product) :slug (:slug product) :name (:name product) :category (:category product)
   :priceRwf (:priceRwf product) :mainImage main-image :isNew (boolean (:isNew product))
   :availability (availability product)})

(defn detail
  "Builds the public product detail body: the summary plus `description`, `details`, `images`
  (already resolved `Image` maps) and `optionGroups`."
  [product main-image images]
  (assoc (summary product main-image)
         :description (:description product) :details (:details product)
         :images images
         :optionGroups (mapv (fn [g] {:name (:name g) :options (mapv #(select-keys % [:id :label :inStock]) (:options g))})
                             (:optionGroups product))))

(defn sort-products
  "Sorts product maps by `sort-key`: \"newest\" (default), \"priceAsc\" or \"priceDesc\"."
  [products sort-key]
  (case sort-key
    "priceAsc" (sort-by (juxt :priceRwf #(- (.toEpochMilli ^java.time.Instant (:createdAt %)))) products)
    "priceDesc" (sort-by (juxt #(- (:priceRwf %)) #(- (.toEpochMilli ^java.time.Instant (:createdAt %)))) products)
    (sort-by #(- (.toEpochMilli ^java.time.Instant (:createdAt %))) products)))

(defn set-availability
  "Applies the quick stock toggle: with `option-id` flips that option; without it sets the product-level flag and
  every option. Returns the updated product, or nil when `option-id` is unknown."
  [product option-id in-stock?]
  (if option-id
    (let [found? (some #(= option-id (:id %)) (mapcat :options (:optionGroups product)))]
      (when found?
        (update product :optionGroups
                (fn [gs] (mapv (fn [g] (update g :options (fn [os] (mapv #(if (= option-id (:id %)) (assoc % :inStock in-stock?) %) os)))) gs)))))
    (-> product
        (assoc :inStock in-stock?)
        (update :optionGroups (fn [gs] (mapv (fn [g] (update g :options (fn [os] (mapv #(assoc % :inStock in-stock?) os)))) gs))))))

(defn resolve-line
  "Resolves one order line `{:productId :optionIds :quantity}` against `product` (nil when unknown).
  Returns `{:ok true :line {...}}` with snapshot fields `:productId :name :optionLabels :unitPriceRwf :quantity
  :lineTotalRwf`, or `{:ok false :reason r :name n}` where `r` is :removed, :unpublished, :soldOut, or
  :badOption (an option that does not belong to the product, repeated within a group, or missing for a group)."
  [product {:keys [productId optionIds quantity]}]
  (cond
    (nil? product) {:ok false :reason :removed :name nil}
    (= :removed (:status product)) {:ok false :reason :removed :name (:name product)}
    (not= :published (:status product)) {:ok false :reason :unpublished :name (:name product)}
    :else
    (let [groups (:optionGroups product)
          by-id (into {} (for [g groups o (:options g)] [(:id o) [g o]]))
          picks (map by-id optionIds)
          group-names (map (comp :name first) picks)
          bad? (or (some nil? picks)
                   (not= (count group-names) (count (set group-names)))
                   (and (seq groups) (not= (set (map :name groups)) (set group-names))))]
      (cond
        bad? {:ok false :reason :badOption :name (:name product)}
        (= "soldOut" (availability product)) {:ok false :reason :soldOut :name (:name product)}
        (some #(not (:inStock (second %))) picks) {:ok false :reason :soldOut :name (:name product)}
        :else {:ok true
               :line {:productId productId :name (:name product)
                      :optionLabels (mapv (comp :label second) picks)
                      :unitPriceRwf (:priceRwf product) :quantity quantity
                      :lineTotalRwf (* quantity (:priceRwf product))}}))))

(defn subtotal
  "Sum of `lineTotalRwf` over resolved `lines`."
  [lines]
  (money/sum (map :lineTotalRwf lines)))

(defn sanitize-slug-query
  "Lower-cases and trims a slug path parameter."
  [s]
  (str/lower-case (str/trim (str s))))
