(ns aurashots.features.catalog.routes
  "Slice `catalog`, HTTP routes (api-contract.md sections 2.2, 2.8 and 3.5): the public service list, the
  basket quote and the admin service CRUD with ordering and soft removal. The public detail route lives in
  `storefront`."
  (:require [aurashots.features.catalog.api :as api]
            [aurashots.features.catalog.core :as core]
            [aurashots.features.catalog.store :as store]
            [aurashots.features.media.api :as media]
            [aurashots.features.merch.api :as merch]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]))

(def ^:private body-schema
  (s/closed-map
   [:name (s/text 1 100)]
   [:shortDescription (s/text 0 200)]
   [:description (s/text 0 5000)]
   [:priceRwf [:maybe [:int {:min 0}]]]
   [:sessionMinutes [:maybe [:int {:min 5 :max 600}]]]
   [:contents (s/closed-map [:editedPictures {:optional true} [:maybe [:int {:min 0}]]]
                            [:videos {:optional true} [:maybe [:int {:min 0}]]]
                            [:outfits {:optional true} [:maybe [:int {:min 0}]]]
                            [:deliveryTime {:optional true} [:maybe (s/text 0 100)]]
                            [:locations {:optional true} [:maybe (s/text 0 200)]])]
   [:conditions (s/text 0 3000)]
   [:relatedProductIds [:vector {:max 20} s/Id]]
   [:images [:vector {:max 12} (s/closed-map [:imageId s/Id] [:alt (s/text 1 200)])]]
   [:featured :boolean]
   [:status [:enum "published" "hidden"]]
   [:sortOrder [:int {:min 0 :max 100000}]]))

(defn- admin-record
  "Builds the admin service record: all stored fields, images expanded for editing, and `needsPrice`."
  [conn service]
  (-> service
      (assoc :images (mapv (fn [{:keys [imageId alt]}]
                             (merge {:imageId imageId :alt alt}
                                    (select-keys (media/image-view conn imageId alt) [:url :thumbUrl :width :height])))
                           (:images service))
             :needsPrice (not (core/priced? (:priceRwf service)))
             :mainImage (when (seq (:images service)) (api/main-image conn service)))))

(defn- validate-body!
  "Applies cross-field and reference checks to the admin `body`, throwing `validationFailed` on problems."
  [conn body]
  (let [fields (cond-> (core/publish-errors body)
                 (seq (media/missing-ids conn (map :imageId (:images body))))
                 (assoc "images" "unknown image id")
                 (not= (count (:relatedProductIds body)) (count (merch/existing-ids conn (:relatedProductIds body))))
                 (assoc "relatedProductIds" "unknown product id"))]
    (when (seq fields) (err/fail! :validationFailed "Some details need attention." fields))))

(defn- save-service!
  "Creates (`existing` nil) or updates a service from validated `body` and returns the stored record."
  [conn body existing]
  (validate-body! conn body)
  (let [slug (when-not existing (core/unique-slug (:name body) #(store/slug-taken? conn %)))]
    (store/save! conn (core/build-service body {:id (refs/new-id) :slug slug :existing existing}))))

(defn- find-active!
  "Returns the non-removed service `id` or throws `notFound`."
  [conn id]
  (let [s (store/get-by-id conn id)]
    (when (or (nil? s) (= :removed (:status s))) (err/fail! :notFound "That service does not exist."))
    s))

(defn routes
  "Returns the route specs of the catalog slice. `deps` keys: `:conn`."
  [{:keys [conn]}]
  (let [staff #{:owner :support}
        id-path {:path (s/closed-map [:id s/Id])}]
    [{:name :catalog/list :method :get :path "/api/services" :public true :no-input true
      :handler (fn [_] (resp/cached {:items (mapv #(api/service-view conn %) (api/published-services conn))}))}
     {:name :catalog/quote :method :post :path "/api/quotes/sessions" :public true
      :schema {:body (s/closed-map [:serviceIds [:vector {:min 1 :max 10} s/Id]])}
      :handler (fn [{{{:keys [serviceIds]} :body} :params}]
                 (let [items (mapv (fn [id] (let [svc (store/get-by-id conn id)]
                                              (core/quote-item id svc (if svc (api/main-image conn svc) (media/placeholder-view conn)))))
                                   serviceIds)]
                   {:status 200 :headers {"Cache-Control" "no-store"}
                    :body {:items items :totalRwf (core/quote-total items)}}))}
     {:name :catalog/admin-list :method :get :path "/api/admin/services" :roles staff
      :schema {:query (s/closed-map [:status {:optional true} [:enum "published" "hidden" "removed"]])}
      :handler (fn [{{{:keys [status]} :query} :params}]
                 (resp/ok {:items (mapv (fn [sv] {:id (:id sv) :name (:name sv) :priceRwf (:priceRwf sv)
                                                  :status (name (:status sv))
                                                  :needsPrice (not (core/priced? (:priceRwf sv)))
                                                  :mainImage (when (seq (:images sv)) (api/main-image conn sv))})
                                        (store/list-by-status conn (if status [(keyword status)] [:published :hidden])))}))}
     {:name :catalog/admin-get :method :get :path "/api/admin/services/:id" :roles staff :schema id-path
      :handler (fn [{{{:keys [id]} :path} :params}] (resp/ok (admin-record conn (find-active! conn id))))}
     {:name :catalog/admin-create :method :post :path "/api/admin/services" :roles staff :schema {:body body-schema}
      :handler (fn [{{body :body} :params}] (resp/created (admin-record conn (save-service! conn body nil))))}
     {:name :catalog/admin-update :method :put :path "/api/admin/services/:id" :roles staff
      :schema (assoc id-path :body body-schema)
      :handler (fn [{{{:keys [id]} :path body :body} :params}]
                 (resp/ok (admin-record conn (save-service! conn body (find-active! conn id)))))}
     {:name :catalog/admin-delete :method :delete :path "/api/admin/services/:id" :roles staff :schema id-path
      :handler (fn [{{{:keys [id]} :path} :params}]
                 (let [sv (find-active! conn id)]
                   (store/save! conn (assoc sv :status :removed))
                   (resp/no-content)))}
     {:name :catalog/admin-order :method :put :path "/api/admin/service-order" :roles staff
      :schema {:body (s/closed-map [:ids [:vector {:max 200} s/Id]])}
      :handler (fn [{{{:keys [ids]} :body} :params}]
                 (when (some #(nil? (store/get-by-id conn %)) ids)
                   (err/fail! :validationFailed "Some services could not be found." {:ids "unknown service id"}))
                 (store/set-order! conn ids)
                 (resp/no-content))}]))
