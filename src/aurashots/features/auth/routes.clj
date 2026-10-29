(ns aurashots.features.auth.routes
  "Slice `auth`, HTTP routes (api-contract.md sections 3.1 and 3.12): admin sign in and out, password flows and the
  owner-only staff accounts. Login failures are rate limited per email and IP; every failure answers the same."
  (:require [aurashots.features.auth.api :as api]
            [aurashots.features.auth.core :as core]
            [aurashots.features.auth.store :as store]
            [aurashots.platform.http-core.limits :as limits]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.shared.errors :as err]
            [aurashots.shared.time :as t]))

(def ^:private password-schema
  [:string {:min 12 :max 200 :error/message "must be at least 12 characters"}])

(def ^:private user-schema
  "The signed-in user `{id name email role}`."
  (rs/obj [:id rs/Id] [:name :string] [:email :string] [:role [:enum "owner" "support"]]))

(def ^:private session-body
  "Body of login and `me`: the user and the CSRF token."
  (rs/obj [:user user-schema] [:csrfToken :string]))

(def ^:private staff-record
  "A staff account row (api-contract.md section 3.12)."
  (rs/obj [:id rs/Id] [:name :string] [:email :string] [:role [:enum "owner" "support"]] [:active :boolean]
          [:invitationPending :boolean] [:lastSignInAt (rs/maybe rs/Instant)]))

(def ^:private staff-created
  "Response of `POST /api/admin/staff`: the staff record plus the one-time invitation link (amendment A1)."
  (rs/obj [:id rs/Id] [:name :string] [:email :string] [:role [:enum "owner" "support"]] [:active :boolean]
          [:invitationPending :boolean] [:lastSignInAt (rs/maybe rs/Instant)]
          [:invitationUrl :string] [:invitationExpiresAt rs/Instant] [:emailQueued :boolean]))

(def ^:private staff-link
  "Response of `POST /api/admin/staff/{id}/reset-password-link` (amendment A1)."
  (rs/obj [:kind [:enum "invitation" "reset"]] [:url :string] [:expiresAt rs/Instant] [:emailQueued :boolean]))

(defn routes
  "Returns the route specs of the auth slice. `deps` keys: `:conn`, `:config`, `:clock`."
  [{:keys [conn config]}]
  (let [pepper (:token-pepper config)
        secure? (get-in config [:http :cookie-secure])
        staff #{:owner :support}
        owner #{:owner}
        id-path {:path (s/closed-map [:id s/Id])}
        link-deps (fn [now] {:conn conn :config config :pepper pepper :now now})]
    [{:name :auth/login :method :post :path "/api/admin/auth/login" :public true
      :schema {:body (s/closed-map [:email s/Email] [:password [:string {:min 1 :max 200}]])}
      :response {200 session-body}
      :handler (fn [{{{:keys [email password]} :body} :params :keys [ip now]}]
                 (let [ms (t/epoch-ms now)]
                   (when (limits/login-blocked? conn email ip ms)
                     (err/fail! :rateLimited "Too many sign-in attempts. Please wait and try again." nil
                                {"Retry-After" (str (limits/login-retry-after ms))}))
                   (if-let [{:keys [user csrf-token cookie-value]} (api/login! conn pepper email password now)]
                     {:status 200
                      :headers {"Cache-Control" "no-store"
                                "Set-Cookie" (core/session-cookie cookie-value secure? (quot (.toSeconds core/session-idle) 1))}
                      :body {:user user :csrfToken csrf-token}}
                     (do (limits/login-failure! conn email ip ms)
                         (err/fail! :unauthenticated "That email or password is not right.")))))}
     {:name :auth/logout :method :post :path "/api/admin/auth/logout" :roles staff :no-input true :response {204 rs/NoBody}
      :handler (fn [{:keys [session-id]}]
                 (api/logout! conn session-id)
                 {:status 204 :headers {"Cache-Control" "no-store" "Set-Cookie" (core/session-cookie "" secure? 0)}})}
     {:name :auth/me :method :get :path "/api/admin/auth/me" :roles staff :no-input true :response {200 session-body}
      :handler (fn [{:keys [user csrf-token]}]
                 (resp/ok {:user (assoc user :role (name (:role user))) :csrfToken csrf-token}))}
     {:name :auth/forgot :method :post :path "/api/admin/auth/forgot-password" :public true :rate-limit :public-post
      :schema {:body (s/closed-map [:email s/Email])} :response {202 (rs/obj [:ok [:= true]])}
      :handler (fn [{{{:keys [email]} :body} :params :keys [now]}]
                 (api/forgot-password! (link-deps now) email)
                 (resp/accepted {:ok true}))}
     {:name :auth/reset :method :post :path "/api/admin/auth/reset-password" :public true :rate-limit :public-post
      :schema {:body (s/closed-map [:token [:string {:min 10 :max 200}]] [:newPassword password-schema])}
      :response {204 rs/NoBody}
      :handler (fn [{{{:keys [token newPassword]} :body} :params :keys [now]}]
                 (when-not (api/redeem-token! conn pepper token newPassword now)
                   (err/fail! :validationFailed "That link is not valid or has expired." {:token "invalid or expired"}))
                 (resp/no-content))}
     {:name :auth/change-password :method :post :path "/api/admin/auth/change-password" :roles staff
      :schema {:body (s/closed-map [:currentPassword [:string {:min 1 :max 200}]] [:newPassword password-schema])}
      :response {204 rs/NoBody}
      :handler (fn [{{{:keys [currentPassword newPassword]} :body} :params user :user}]
                 (when-not (api/change-password! conn (:id user) currentPassword newPassword)
                   (err/fail! :validationFailed "Your current password is not right." {:currentPassword "is not right"}))
                 (resp/no-content))}
     {:name :auth/staff-list :method :get :path "/api/admin/staff" :roles owner :no-input true :response {200 (rs/items staff-record)}
      :handler (fn [_] (resp/ok {:items (mapv core/staff-view (store/list-users conn))}))}
     {:name :auth/staff-create :method :post :path "/api/admin/staff" :roles owner
      :schema {:body (s/closed-map [:name (s/text 2 100)] [:email s/Email])} :response {201 staff-created}
      :handler (fn [{{body :body} :params :keys [now]}]
                 (resp/created (api/create-staff! (link-deps now) body)))}
     {:name :auth/staff-get :method :get :path "/api/admin/staff/:id" :roles owner :schema id-path :response {200 staff-record}
      :handler (fn [{{{:keys [id]} :path} :params}]
                 (resp/ok (core/staff-view (or (store/user-by-id conn id) (err/fail! :notFound "That staff member does not exist.")))))}
     {:name :auth/staff-patch :method :patch :path "/api/admin/staff/:id" :roles owner
      :schema (assoc id-path :body (s/closed-map [:name {:optional true} (s/text 2 100)] [:active {:optional true} :boolean]))
      :response {200 staff-record}
      :handler (fn [{{{:keys [id]} :path body :body} :params}]
                 (let [u (or (store/user-by-id conn id) (err/fail! :notFound "That staff member does not exist."))]
                   (when (empty? body) (err/fail! :validationFailed "Nothing to change." {:name "give a name or active"}))
                   (when (and (= :owner (:role u)) (false? (:active body)))
                     (err/fail! :conflict "The owner account cannot be switched off."))
                   (let [u' (store/save-user! conn (merge u (select-keys body [:name :active])))]
                     (when (false? (:active body)) (store/delete-user-sessions! conn id))
                     (resp/ok (core/staff-view u')))))}
     {:name :auth/staff-reset-link :method :post :path "/api/admin/staff/:id/reset-password-link" :roles owner
      :schema id-path :response {200 staff-link}
      :handler (fn [{{{:keys [id]} :path} :params :keys [now]}]
                 (let [u (or (store/user-by-id conn id) (err/fail! :notFound "That staff member does not exist."))]
                   (resp/ok (api/staff-link! (link-deps now) u))))}
     {:name :auth/staff-delete :method :delete :path "/api/admin/staff/:id" :roles owner :schema id-path :response {204 rs/NoBody}
      :handler (fn [{{{:keys [id]} :path} :params user :user}]
                 (let [u (or (store/user-by-id conn id) (err/fail! :notFound "That staff member does not exist."))]
                   (when (= :owner (:role u)) (err/fail! :conflict "The owner account cannot be removed."))
                   (when (= id (:id user)) (err/fail! :conflict "You cannot remove your own account."))
                   (store/delete-user! conn id)
                   (resp/no-content)))}]))
