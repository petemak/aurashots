(ns aurashots.features.documents.routes
  "Slice `documents`, HTTP routes (api-contract.md sections 2.10 and 3.13): public document reading and the
  owner-only document administration and acceptance listing."
  (:require [aurashots.features.documents.api :as api]
            [aurashots.features.documents.core :as core]
            [aurashots.features.documents.store :as store]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]))

(defn- admin-doc
  "Admin view of document `slug` (throws `notFound` when unknown)."
  [conn slug]
  (let [{:keys [doc version]} (or (api/current-document conn slug) (err/fail! :notFound "That document does not exist."))]
    (core/admin-view doc version (store/list-versions conn slug))))

(def ^:private public-doc
  "A document version as served to customers (api-contract.md section 2.10)."
  (rs/obj [:slug :string] [:title :string] [:version :int] [:publishedAt rs/Instant] [:requiresAcceptance :boolean]
          [:summaryPoints [:vector :string]] [:body :string]))

(def ^:private admin-doc-schema
  "Admin document: the current text plus `reviewStatus` and the version list (api-contract.md section 3.13)."
  (rs/obj [:slug :string] [:title :string] [:version :int] [:publishedAt rs/Instant] [:requiresAcceptance :boolean]
          [:summaryPoints [:vector :string]] [:body :string] [:reviewStatus [:enum "draft" "reviewed"]]
          [:versions [:vector (rs/obj [:version :int] [:publishedAt rs/Instant] [:publishedByName :string]
                                      [:changeNote (rs/maybe :string)])]]))

(def ^:private acceptance
  "An acceptance record (api-contract.md section 2.11 step 4), as listed to the owner."
  (rs/obj [:id rs/Id] [:documentSlug :string] [:version :int] [:bodyHash :string]
          [:subjectType [:enum "bookingRequest" "merchandiseOrder"]] [:subjectId rs/Id] [:subjectReference :string]
          [:customerName :string] [:customerEmail :string] [:customerPhone :string] [:acceptedAt rs/Instant]
          [:ipAddress (rs/maybe :string)] [:userAgent (rs/maybe :string)]))

(defn routes
  "Returns the route specs of the documents slice. `deps` keys: `:conn`."
  [{:keys [conn]}]
  (let [owner #{:owner}
        slug-path {:path (s/closed-map [:slug (s/text 1 100)])}]
    [{:name :documents/public :method :get :path "/api/documents/:slug" :public true
      :schema {:path (s/closed-map [:slug (s/text 1 100)])
               :query (s/closed-map [:version {:optional true} [:int {:min 1}]])}
      :response {200 public-doc}
      :handler (fn [{{{:keys [slug]} :path {:keys [version]} :query} :params}]
                 (resp/cached (or (api/public-document conn slug version)
                                  (err/fail! :notFound "That document does not exist."))))}
     {:name :documents/admin-list :method :get :path "/api/admin/documents" :roles owner :no-input true
      :response {200 (rs/items (rs/obj [:slug :string] [:title :string] [:currentVersion :int] [:publishedAt rs/Instant]
                                       [:reviewStatus [:enum "draft" "reviewed"]] [:requiresAcceptance :boolean]))}
      :handler (fn [_]
                 (resp/ok {:items (mapv (fn [d] (core/admin-list-item d (store/get-version conn (:slug d) (:currentVersion d))))
                                        (store/list-docs conn))}))}
     {:name :documents/admin-get :method :get :path "/api/admin/documents/:slug" :roles owner :schema slug-path
      :response {200 admin-doc-schema}
      :handler (fn [{{{:keys [slug]} :path} :params}] (resp/ok (admin-doc conn slug)))}
     {:name :documents/admin-publish :method :post :path "/api/admin/documents/:slug/versions" :roles owner
      :response {201 public-doc}
      :schema (assoc slug-path :body (s/closed-map [:body (s/text 1 100000)]
                                                   [:summaryPoints [:vector {:max 10} (s/text 1 300)]]
                                                   [:changeNote (s/text 0 300)]))
      :handler (fn [{{{:keys [slug]} :path body :body} :params user :user now :now}]
                 (let [{:keys [doc version]} (or (api/current-document conn slug) (err/fail! :notFound "That document does not exist."))
                       ver (core/new-version slug version body (:name user) now)]
                   (store/publish-version! conn doc ver)
                   (resp/created (core/public-view doc ver))))}
     {:name :documents/admin-reviewed :method :post :path "/api/admin/documents/:slug/mark-reviewed" :roles owner
      :schema slug-path :response {200 admin-doc-schema}
      :handler (fn [{{{:keys [slug]} :path} :params}]
                 (let [{:keys [doc]} (or (api/current-document conn slug) (err/fail! :notFound "That document does not exist."))]
                   (store/save-doc! conn (assoc doc :reviewStatus :reviewed))
                   (resp/ok (admin-doc conn slug))))}
     {:name :documents/admin-acceptances :method :get :path "/api/admin/acceptances" :roles owner
      :schema {:query (s/closed-map [:subjectReference {:optional true} (s/text 1 40)])}
      :response {200 (rs/items acceptance)}
      :handler (fn [{{{:keys [subjectReference]} :query} :params}]
                 (resp/ok {:items (if subjectReference
                                    (api/acceptances-for conn subjectReference)
                                    (store/recent-acceptances conn 200))}))}]))
