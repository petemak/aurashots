(ns aurashots.features.merch.routes
  "Slice `merch`, HTTP routes (api-contract.md sections 2.5 and 3.6): the public product list and the admin
  product CRUD with the quick availability toggle. The public detail route lives in `storefront`."
  (:require [aurashots.features.media.api :as media]
            [aurashots.features.merch.api :as api]
            [aurashots.features.merch.core :as core]
            [aurashots.features.merch.store :as store]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]))

(def ^:private categories [:enum "clothing" "prints" "framed-art"])

(def ^:private body-schema
  (s/closed-map
   [:name (s/text 1 100)]
   [:category categories]
   [:description (s/text 0 5000)]
   [:details (s/text 0 3000)]
   [:priceRwf [:int {:min 1}]]
   [:optionGroups [:vector {:max 5}
                   (s/closed-map [:name (s/text 1 50)]
                                 [:options [:vector {:max 30}
                                            (s/closed-map [:id {:optional true} [:maybe s/Id]]
                                                          [:label (s/text 1 50)] [:inStock :boolean])]])]]
   [:images [:vector {:max 12} (s/closed-map [:imageId s/Id] [:alt (s/text 1 200)])]]
   [:isNew :boolean]
   [:featured :boolean]
   [:status [:enum "published" "hidden"]]))

(def ^:private admin-image
  "An image attached to a product in the admin record."
  (rs/obj [:imageId rs/Id] [:alt :string] [:url :string] [:thumbUrl :string] [:width :int] [:height :int]))

(def ^:private option-groups
  "Option groups with option ids (api-contract.md section 3.6 body)."
  [:vector (rs/obj [:name :string] [:options [:vector (rs/obj [:id rs/Id] [:label :string] [:inStock :boolean])]])])

(def ^:private admin-product
  "Full admin product record: the body fields plus id, slug, `createdAt`, the product-level `inStock` flag and the
  derived `availability`."
  (rs/obj [:id rs/Id] [:slug :string] [:name :string] [:category [:enum "clothing" "prints" "framed-art"]]
          [:description :string] [:details :string] [:priceRwf rs/Money] [:optionGroups option-groups]
          [:images [:vector admin-image]] [:isNew :boolean] [:featured :boolean] [:status [:enum "published" "hidden"]]
          [:createdAt rs/Instant] [:inStock :boolean] [:availability [:enum "inStock" "soldOut"]]))

(def ^:private admin-product-row
  "A row of the admin product list."
  (rs/obj [:id rs/Id] [:slug :string] [:name :string] [:category [:enum "clothing" "prints" "framed-art"]]
          [:priceRwf rs/Money] [:status [:enum "published" "hidden" "removed"]] [:isNew :boolean] [:featured :boolean]
          [:availability [:enum "inStock" "soldOut"]] [:mainImage (rs/maybe rs/Image)]))

(defn- admin-record
  "Builds the admin product record: the stored fields with images expanded for editing."
  [conn p]
  (-> p
      (dissoc :images)
      (assoc :images (mapv (fn [{:keys [imageId alt]}]
                             (let [v (media/image-view conn imageId alt)]
                               (merge {:imageId imageId :alt alt} (select-keys v [:url :thumbUrl :width :height]))))
                           (:images p))
             :availability (core/availability p))))

(defn- admin-list-item
  "A compact admin list row for product `p`."
  [conn p]
  (assoc (select-keys p [:id :slug :name :category :priceRwf :status :isNew :featured])
         :availability (core/availability p)
         :mainImage (when (seq (:images p)) (api/main-image conn p))))

(defn- validate-refs!
  "Ensures every referenced image exists; throws `validationFailed` otherwise."
  [conn body]
  (let [missing (media/missing-ids conn (map :imageId (:images body)))]
    (when (seq missing)
      (err/fail! :validationFailed "Some images could not be found." {:images "unknown image id"}))))

(defn- save-product!
  "Creates (`existing` nil) or updates a product from validated `body`; returns the saved record."
  [{:keys [conn clock]} body existing]
  (validate-refs! conn body)
  (let [body (-> body (update :status keyword))
        slug (when-not existing (core/unique-slug (:name body) #(store/slug-taken? conn %)))
        p (core/build-product body {:id (refs/new-id) :slug slug :now ((:now-fn clock)) :existing existing})]
    (store/save! conn p)))

(defn routes
  "Returns the route specs of the merch slice. `deps` keys: `:conn`, `:clock`."
  [{:keys [conn] :as deps}]
  (let [id-path {:path (s/closed-map [:id s/Id])}
        staff #{:owner :support}]
    [{:name :merch/list :method :get :path "/api/products" :public true
      :schema {:query (into [:map {:closed true}
                             [:category {:optional true} categories]
                             [:sort {:optional true} [:enum "newest" "priceAsc" "priceDesc"]]
                             [:featured {:optional true} s/Bool]]
                            s/Pagination)}
      :response {200 (rs/paginated rs/ProductSummary)}
      :handler (fn [{{{:keys [page pageSize] :as q} :query} :params}]
                 (resp/cached (resp/paginated (api/list-published conn q) page pageSize #(api/summary conn %))))}
     {:name :merch/admin-list :method :get :path "/api/admin/products" :roles staff
      :schema {:query (into [:map {:closed true}
                             [:status {:optional true} [:enum "published" "hidden" "removed"]]
                             [:category {:optional true} categories]]
                            s/Pagination)}
      :response {200 (rs/paginated admin-product-row)}
      :handler (fn [{{{:keys [page pageSize status category]} :query} :params}]
                 (let [ps (->> (store/list-by-status conn (if status [(keyword status)] [:published :hidden]))
                               (filter #(or (nil? category) (= category (:category %))))
                               (#(core/sort-products % "newest")))]
                   (resp/ok (resp/paginated ps page pageSize #(admin-list-item conn %)))))}
     {:name :merch/admin-get :method :get :path "/api/admin/products/:id" :roles staff :schema id-path
      :response {200 admin-product}
      :handler (fn [{{{:keys [id]} :path} :params}]
                 (let [p (store/get-by-id conn id)]
                   (when (or (nil? p) (= :removed (:status p))) (err/fail! :notFound "That product does not exist."))
                   (resp/ok (admin-record conn p))))}
     {:name :merch/admin-create :method :post :path "/api/admin/products" :roles staff :schema {:body body-schema}
      :response {201 admin-product}
      :handler (fn [{{body :body} :params}] (resp/created (admin-record conn (save-product! deps body nil))))}
     {:name :merch/admin-update :method :put :path "/api/admin/products/:id" :roles staff
      :schema (assoc id-path :body body-schema) :response {200 admin-product}
      :handler (fn [{{{:keys [id]} :path body :body} :params}]
                 (let [p (store/get-by-id conn id)]
                   (when (or (nil? p) (= :removed (:status p))) (err/fail! :notFound "That product does not exist."))
                   (resp/ok (admin-record conn (save-product! deps body p)))))}
     {:name :merch/admin-delete :method :delete :path "/api/admin/products/:id" :roles staff :schema id-path
      :response {204 rs/NoBody}
      :handler (fn [{{{:keys [id]} :path} :params}]
                 (let [p (store/get-by-id conn id)]
                   (when-not p (err/fail! :notFound "That product does not exist."))
                   (store/save! conn (assoc p :status :removed))
                   (resp/no-content)))}
     {:name :merch/availability :method :patch :path "/api/admin/products/:id/availability" :roles staff
      :response {200 admin-product}
      :schema (assoc id-path :body (s/closed-map [:optionId {:optional true} [:maybe s/Id]] [:inStock :boolean]))
      :handler (fn [{{{:keys [id]} :path {:keys [optionId inStock]} :body} :params}]
                 (let [p (store/get-by-id conn id)]
                   (when (or (nil? p) (= :removed (:status p))) (err/fail! :notFound "That product does not exist."))
                   (let [p' (or (core/set-availability p optionId inStock)
                                (err/fail! :validationFailed "That option does not belong to this product." {:optionId "unknown option"}))]
                     (store/save! conn p')
                     (resp/ok (admin-record conn p')))))}]))
