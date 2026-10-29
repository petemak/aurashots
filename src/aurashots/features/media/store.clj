(ns aurashots.features.media.store
  "Slice `media`, data access (architecture.md section 3.2): `image` entities `:image/id` and `:image/data`
  `{:id :width :height :createdAt}`. Also answers whether an image is referenced from elsewhere, by a data-level
  reverse lookup over a configured list of reference attributes (no code dependency on other slices)."
  (:require [datalevin.core :as d]))

(def referencing-attributes
  "Reference attributes that point at `image` entities (services, products, portfolio items)."
  [:service/imageRefs :product/imageRefs :portfolio/image])

(defn insert-image!
  "Stores image metadata `{:id :width :height :createdAt}`. Side effect: database transaction."
  [conn image]
  (d/transact! conn [{:image/id (:id image) :image/data image}])
  image)

(defn get-image
  "Returns the stored image data map for `id`, or nil."
  [conn id]
  (:image/data (d/pull (d/db conn) [:image/data] [:image/id id])))

(defn in-use?
  "True when any entity references the image `id` through one of `referencing-attributes`."
  [conn id]
  (let [db (d/db conn)]
    (boolean
     (when-let [eid (d/q '[:find ?i . :in $ ?id :where [?i :image/id ?id]] db id)]
       (some (fn [a] (seq (d/q '[:find ?e :in $ ?a ?i :where [?e ?a ?i]] db a eid)))
             referencing-attributes)))))

(defn delete-image!
  "Deletes the image entity `id`. Side effect: database transaction."
  [conn id]
  (d/transact! conn [[:db/retractEntity [:image/id id]]]))
