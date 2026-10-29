(ns aurashots.features.site.routes
  "Slice `site`, HTTP routes (api-contract.md sections 2.1 and 3.11): public site info and the admin settings
  endpoints. Handlers are thin; shaping lives in `site.core`."
  (:require [aurashots.features.site.api :as api]
            [aurashots.features.site.core :as core]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]))

(def ^:private day [:enum "mon" "tue" "wed" "thu" "fri" "sat" "sun"])

(def ^:private settings-body
  (s/closed-map
   [:phone [:maybe (s/text 0 30)]]
   [:whatsapp [:maybe (s/text 0 30)]]
   [:email [:maybe [:or s/Email [:enum "OWNER_EMAIL"]]]]
   [:address [:maybe (s/text 0 300)]]
   [:social (s/closed-map [:instagram [:maybe (s/text 0 300)]] [:tiktok [:maybe (s/text 0 300)]]
                          [:facebook [:maybe (s/text 0 300)]])]
   [:openingHours [:vector {:max 7} (s/closed-map [:days [:vector {:min 1} day]] [:open s/Time] [:close s/Time])]]
   [:booking (s/closed-map [:minNoticeHours [:int {:min 0 :max 720}]]
                           [:windowDays [:int {:min 1 :max 730}]]
                           [:defaultSessionMinutes [:maybe [:int {:min 5 :max 600}]]]
                           [:slotMinutes {:optional true} :int])]
   [:deliveryAreas [:vector {:max 50}
                    (s/closed-map [:id {:optional true} [:maybe s/Id]] [:name (s/text 1 100)]
                                  [:feeRwf [:maybe [:int {:min 0}]]] [:active :boolean])]]
   [:portfolioCategories [:vector {:max 50}
                          (s/closed-map [:id {:optional true} [:maybe s/Id]] [:name (s/text 1 60)])]]))

(defn routes
  "Returns the route specs of the site slice. `deps` keys: `:conn`, `:config`, `:category-in-use?`
  (function `(conn category-id) -> bool` injected from the portfolio slice by `system`)."
  [{:keys [conn config category-in-use?]}]
  (let [owner (:owner-email config)]
    [{:name :site/info :method :get :path "/api/site-info" :public true :no-input true
      :handler (fn [_] (resp/cached (api/public-info conn owner)))}
     {:name :site/admin-get :method :get :path "/api/admin/settings" :roles #{:owner :support} :no-input true
      :handler (fn [_] (resp/ok (core/admin-view (api/settings conn) owner)))}
     {:name :site/admin-put :method :put :path "/api/admin/settings" :roles #{:owner :support}
      :schema {:body settings-body}
      :handler (fn [{{body :body} :params}]
                 (let [fields (core/validate-update body)
                       _ (when (seq fields) (err/fail! :validationFailed "Some details need attention." fields))
                       old (api/settings conn)
                       new (core/apply-update body)
                       in-use (filter #(category-in-use? conn %) (core/removed-category-ids old new))]
                   (when (seq in-use)
                     (err/fail! :conflict "A category that still has portfolio photos cannot be removed."))
                   (api/save-settings! conn new)
                   (resp/ok (core/admin-view new owner))))}]))
