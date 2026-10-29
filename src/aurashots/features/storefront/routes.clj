(ns aurashots.features.storefront.routes
  "Slice `storefront`, HTTP routes (api-contract.md sections 2.3 and 2.6): the public service and product
  detail endpoints."
  (:require [aurashots.features.storefront.api :as api]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]))

(def ^:private service-detail
  "Response of `GET /api/services/{slug}` (api-contract.md section 2.3)."
  (rs/obj [:id rs/Id] [:slug :string] [:name :string] [:shortDescription :string] [:priceRwf rs/Money]
          [:sessionMinutes (rs/maybe :int)] [:featured :boolean] [:mainImage rs/Image] [:contents rs/Contents]
          [:description :string] [:conditions :string] [:images [:vector rs/Image]]
          [:relatedProducts [:vector rs/ProductSummary]] [:relatedServices [:vector rs/Service]]))

(def ^:private product-detail
  "Response of `GET /api/products/{slug}` (api-contract.md section 2.6)."
  (rs/obj [:id rs/Id] [:slug :string] [:name :string] [:category [:enum "clothing" "prints" "framed-art"]]
          [:priceRwf rs/Money] [:mainImage rs/Image] [:isNew :boolean] [:availability [:enum "inStock" "soldOut"]]
          [:description :string] [:details :string] [:images [:vector rs/Image]]
          [:optionGroups [:vector (rs/obj [:name :string]
                                          [:options [:vector (rs/obj [:id rs/Id] [:label :string] [:inStock :boolean])]])]]
          [:relatedServiceIds [:vector rs/Id]]))

(defn routes
  "Returns the route specs of the storefront slice. `deps` keys: `:conn`."
  [{:keys [conn]}]
  [{:name :storefront/service :method :get :path "/api/services/:slug" :public true
    :schema {:path (s/closed-map [:slug (s/text 1 120)])} :response {200 service-detail}
    :handler (fn [{{{:keys [slug]} :path} :params}]
               (resp/cached (or (api/service-detail conn slug) (err/fail! :notFound "That service does not exist."))))}
   {:name :storefront/product :method :get :path "/api/products/:slug" :public true
    :schema {:path (s/closed-map [:slug (s/text 1 120)])} :response {200 product-detail}
    :handler (fn [{{{:keys [slug]} :path} :params}]
               (resp/cached (or (api/product-detail conn slug) (err/fail! :notFound "That product does not exist."))))}])
