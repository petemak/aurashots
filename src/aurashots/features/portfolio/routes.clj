(ns aurashots.features.portfolio.routes
  "Slice `portfolio`, HTTP routes (api-contract.md sections 2.4 and 3.8): the public portfolio list and the admin
  portfolio CRUD and ordering."
  (:require [aurashots.features.media.api :as media]
            [aurashots.features.portfolio.core :as core]
            [aurashots.features.portfolio.store :as store]
            [aurashots.features.site.api :as site]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]))

(def ^:private body-schema
  (s/closed-map [:imageId s/Id] [:caption (s/text 0 200)] [:description (s/text 1 300)]
                [:categoryId s/Id] [:featured :boolean]))

(def ^:private public-item
  "Public portfolio item (api-contract.md section 2.4)."
  (rs/obj [:id rs/Id] [:image rs/Image] [:caption :string] [:description :string] [:categoryId rs/Id]
          [:categoryName (rs/maybe :string)]))

(def ^:private admin-item-schema
  "Admin portfolio item (api-contract.md section 3.8)."
  (rs/obj [:id rs/Id] [:image rs/Image] [:caption :string] [:description :string] [:categoryId rs/Id]
          [:featured :boolean] [:sortOrder :int]))

(defn- admin-item
  "Admin view of an item record."
  [conn item]
  (core/admin-view item (or (media/image-view conn (:imageId item) (:description item)) (media/placeholder-view conn))))

(defn- validate-refs!
  "Throws `validationFailed` when the image or category in `body` does not exist."
  [conn body]
  (let [fields (cond-> {}
                 (seq (media/missing-ids conn [(:imageId body)])) (assoc "imageId" "unknown image")
                 (nil? (site/category conn (:categoryId body))) (assoc "categoryId" "unknown category"))]
    (when (seq fields) (err/fail! :validationFailed "Some details need attention." fields))))

(defn- save-item!
  "Creates or updates an item from validated `body`; returns the admin view."
  [{:keys [conn clock]} body existing]
  (validate-refs! conn body)
  (let [next-order (inc (reduce max -1 (map :sortOrder (store/list-all conn))))
        item (core/build-item body {:id (refs/new-id) :sort-order next-order :now ((:now-fn clock)) :existing existing})]
    (store/save! conn item)
    (admin-item conn item)))

(defn- find-item!
  "Returns the item `id` or throws `notFound`."
  [conn id]
  (or (store/get-by-id conn id) (err/fail! :notFound "That photo does not exist.")))

(defn routes
  "Returns the route specs of the portfolio slice. `deps` keys: `:conn`, `:clock`."
  [{:keys [conn] :as deps}]
  (let [staff #{:owner :support}
        id-path {:path (s/closed-map [:id s/Id])}]
    [{:name :portfolio/list :method :get :path "/api/portfolio" :public true
      :schema {:query (into [:map {:closed true}
                             [:category {:optional true} (s/text 1 100)]
                             [:featured {:optional true} s/Bool]]
                            s/Pagination)}
      :response {200 (rs/paginated public-item)}
      :handler (fn [{{{:keys [category featured page pageSize]} :query} :params}]
                 (let [items (->> (store/list-all conn)
                                  (filter #(or (nil? category) (= category (:categoryId %))))
                                  (filter #(or (nil? featured) (= featured (boolean (:featured %))))))]
                   (resp/cached
                    (resp/paginated items page pageSize
                                    (fn [it] (core/public-view
                                              it
                                              (or (media/image-view conn (:imageId it) (:description it)) (media/placeholder-view conn))
                                              (:name (site/category conn (:categoryId it)))))))))}
     {:name :portfolio/admin-list :method :get :path "/api/admin/portfolio" :roles staff
      :schema {:query (into [:map {:closed true}] s/Pagination)} :response {200 (rs/paginated admin-item-schema)}
      :handler (fn [{{{:keys [page pageSize]} :query} :params}]
                 (resp/ok (resp/paginated (store/list-all conn) page pageSize #(admin-item conn %))))}
     {:name :portfolio/admin-create :method :post :path "/api/admin/portfolio" :roles staff :schema {:body body-schema}
      :response {201 admin-item-schema}
      :handler (fn [{{body :body} :params}] (resp/created (save-item! deps body nil)))}
     {:name :portfolio/admin-update :method :put :path "/api/admin/portfolio/:id" :roles staff
      :schema (assoc id-path :body body-schema) :response {200 admin-item-schema}
      :handler (fn [{{{:keys [id]} :path body :body} :params}] (resp/ok (save-item! deps body (find-item! conn id))))}
     {:name :portfolio/admin-order :method :put :path "/api/admin/portfolio-order" :roles staff
      :schema {:body (s/closed-map [:ids [:vector {:max 500} s/Id]])} :response {204 rs/NoBody}
      :handler (fn [{{{:keys [ids]} :body} :params}]
                 (when (some #(nil? (store/get-by-id conn %)) ids)
                   (err/fail! :validationFailed "Some photos could not be found." {:ids "unknown id"}))
                 (store/set-order! conn ids)
                 (resp/no-content))}
     {:name :portfolio/admin-delete :method :delete :path "/api/admin/portfolio/:id" :roles staff :schema id-path :response {204 rs/NoBody}
      :handler (fn [{{{:keys [id]} :path} :params}]
                 (find-item! conn id)
                 (store/delete! conn id)
                 (resp/no-content))}]))
