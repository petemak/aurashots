(ns aurashots.features.storefront.routes
  "Slice `storefront`, HTTP routes (api-contract.md sections 2.3 and 2.6): the public service and product
  detail endpoints."
  (:require [aurashots.features.storefront.api :as api]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]))

(defn routes
  "Returns the route specs of the storefront slice. `deps` keys: `:conn`."
  [{:keys [conn]}]
  [{:name :storefront/service :method :get :path "/api/services/:slug" :public true
    :schema {:path (s/closed-map [:slug (s/text 1 120)])}
    :handler (fn [{{{:keys [slug]} :path} :params}]
               (resp/cached (or (api/service-detail conn slug) (err/fail! :notFound "That service does not exist."))))}
   {:name :storefront/product :method :get :path "/api/products/:slug" :public true
    :schema {:path (s/closed-map [:slug (s/text 1 120)])}
    :handler (fn [{{{:keys [slug]} :path} :params}]
               (resp/cached (or (api/product-detail conn slug) (err/fail! :notFound "That product does not exist."))))}])
