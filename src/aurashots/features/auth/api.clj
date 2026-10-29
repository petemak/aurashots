(ns aurashots.features.auth.api
  "Slice `auth`, public API (architecture.md sections 2.4 and 5): session resolution for the HTTP layer
  (injected into `http-core` by `system`), login, password and token workflows, staff creation, and the owner
  bootstrap used by the `create-owner` command. Passwords use argon2id; session ids and tokens are stored only as
  keyed hashes."
  (:require [aurashots.features.auth.core :as core]
            [aurashots.features.auth.store :as store]
            [aurashots.features.notifications.api :as notify]
            [aurashots.platform.crypto :as crypto]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]
            [aurashots.shared.time :as t]
            [buddy.hashers :as hashers]))

(def ^:private dummy-hash
  "A valid hash verified when the email is unknown, so that response time does not reveal which emails exist."
  (delay (hashers/derive "not-a-real-password" {:alg :argon2id})))

(defn hash-password
  "Hashes `password` with argon2id."
  [password]
  (hashers/derive password {:alg :argon2id}))

(defn- password-ok?
  "True when `password` matches `hash` (nil hash never matches)."
  [password hash]
  (boolean (:valid (hashers/verify password (or hash @dummy-hash)))) )

(defn csrf-token
  "Derives the CSRF token of the session whose cookie value is `raw`: an HMAC, so nothing extra is stored."
  [pepper raw]
  (crypto/hmac-hex pepper (str "csrf:" raw)))

(defn- session-hash
  "Keyed hash of a raw session id."
  [pepper raw]
  (crypto/hmac-hex pepper (str "session:" raw)))

(defn resolve-session
  "Looks up the session for cookie value `raw` at `now` (an Instant). Returns
  `{:user {:id :name :email :role(kw)} :csrf-token t :session-id hash}` or nil when the cookie is unknown, expired or
  the user is switched off. Side effect: slides the expiry forward when the session was last touched over 10
  minutes ago."
  [conn pepper raw now]
  (let [h (session-hash pepper raw)
        ms (t/epoch-ms now)]
    (when-let [s (store/get-session conn h)]
      (when (core/session-valid? s ms)
        (when-let [user (store/user-by-id conn (:userId s))]
          (when (:active user)
            (when (> (- ms (:lastSeenAt s)) 600000)
              (store/touch-session! conn h ms (core/new-expiry ms)))
            {:user (core/handler-user user) :csrf-token (csrf-token pepper raw) :session-id h}))))))

(defn- start-session!
  "Creates a session for `user`; returns the raw cookie value."
  [conn pepper user now]
  (let [raw (crypto/random-token 32) ms (t/epoch-ms now)]
    (store/save-session! conn {:hash (session-hash pepper raw) :user-id (:id user) :now ms :expires-at (core/new-expiry ms)})
    raw))

(defn login!
  "Checks `email` and `password`. Returns `{:user view :csrf-token t :cookie-value raw}` or nil when the
  credentials are wrong or the user is switched off (callers answer identically for all failures). On success
  records the sign-in time and creates a session."
  [conn pepper email password now]
  (let [user (store/user-by-email conn (.toLowerCase ^String email))
        ok? (password-ok? password (:passwordHash user))]
    (when (and user ok? (:active user) (:passwordHash user))
      (store/save-user! conn (assoc user :lastSignInAt now))
      (let [raw (start-session! conn pepper user now)]
        {:user (core/session-user user) :csrf-token (csrf-token pepper raw) :cookie-value raw}))))

(defn logout!
  "Ends the session with keyed hash `session-id` (as returned by `resolve-session`)."
  [conn session-id]
  (store/delete-session! conn session-id))

(defn issue-token!
  "Creates a single-use password token for `user-id` valid for `ttl` (a `Duration`); returns the raw token."
  [conn pepper user-id now ttl]
  (let [raw (crypto/random-token 32)]
    (store/save-token! conn {:hash (crypto/hmac-hex pepper (str "ptoken:" raw)) :user-id user-id
                             :expires-at (+ (t/epoch-ms now) (.toMillis ^java.time.Duration ttl))})
    raw))

(defn reset-link
  "The admin URL for choosing a password with raw token `raw` under `base-url`."
  [base-url raw]
  (str base-url "/admin/reset-password?token=" raw))

(defn redeem-token!
  "Sets a new password for the owner of the raw token `raw`. Returns true on success; false when the token is
  unknown, used or expired. Side effects: stores the hash, clears `invitationPending`, ends all of the user's
  sessions, marks the token used (all in sequence; the token is checked first)."
  [conn pepper raw new-password now]
  (let [h (crypto/hmac-hex pepper (str "ptoken:" raw))
        tok (store/get-token conn h)]
    (boolean
     (when (and tok (not (:used tok)) (> (:expiresAt tok) (t/epoch-ms now)))
       (when-let [user (store/user-by-id conn (:userId tok))]
         (store/save-user! conn (assoc user :passwordHash (hash-password new-password) :invitationPending false))
         (store/use-token! conn h)
         (store/delete-user-sessions! conn (:id user))
         true)))))

(defn change-password!
  "Changes the password of `user-id` after verifying `current`. Returns true, or false when `current` is wrong."
  [conn user-id current new-password]
  (let [user (store/user-by-id conn user-id)]
    (boolean
     (when (and user (password-ok? current (:passwordHash user)))
       (store/save-user! conn (assoc user :passwordHash (hash-password new-password)))
       true))))

(defn create-owner!
  "Creates the single owner account (bootstrap command). `params` has `:name :email`; `deps` has `:conn :pepper
  :base-url :now`. Returns the one-time password-set link. Throws `conflict` when an owner already exists."
  [{:keys [conn pepper base-url now]} {:keys [name email]}]
  (when (store/owner-exists? conn) (err/fail! :conflict "An owner account already exists."))
  (let [user (core/new-user {:id (refs/new-id) :name name :email (.toLowerCase ^String email) :role :owner} now)]
    (store/save-user! conn user)
    (reset-link base-url (issue-token! conn pepper (:id user) now core/invitation-token-ttl))))

(defn send-password-link!
  "Issues a fresh token for `user` (earlier unused tokens of the user stop working) and returns
  `{:kind \"invitation\"|\"reset\" :url :expires-at :email-queued}`. The email is queued only when SMTP is
  configured. The raw token appears only in the returned URL; the database holds its keyed hash. `deps` has
  `:conn :config :pepper :now`. Side effects: token and message writes."
  [{:keys [conn config pepper now]} user]
  (let [pending? (boolean (:invitationPending user))
        ttl (if pending? core/invitation-token-ttl core/reset-token-ttl)
        expires-at (.plus ^java.time.Instant now ttl)
        _ (store/invalidate-user-tokens! conn (:id user))
        raw (issue-token! conn pepper (:id user) now ttl)
        url (reset-link (:public-base-url config) raw)
        email? (boolean (and (get-in config [:smtp :user]) (get-in config [:smtp :password])))]
    (when email?
      (let [{:keys [tx]} (notify/queue {:conn conn :config config :now now}
                                       [{:template (if pending? :staffInvitation :passwordReset)
                                         :to (:email user) :subject-id (:id user)
                                         :vars {:name (:name user) :link url :expires (core/expires-text expires-at)}}])]
        (store/transact! conn tx)))
    {:kind (if pending? "invitation" "reset") :url url :expires-at expires-at :email-queued email?}))

(defn create-staff!
  "Creates a support user (invitation pending) and issues the invitation link. Throws `conflict` for a duplicate
  email. Returns the staff view plus `:invitationUrl :invitationExpiresAt :emailQueued`."
  [{:keys [conn now] :as deps} {:keys [name email]}]
  (let [email (.toLowerCase ^String email)]
    (when (store/user-by-email conn email) (err/fail! :conflict "A staff member with that email already exists."))
    (let [user (store/save-user! conn (core/new-user {:id (refs/new-id) :name name :email email :role :support} now))
          {:keys [url expires-at email-queued]} (send-password-link! deps user)]
      (assoc (core/staff-view user) :invitationUrl url :invitationExpiresAt expires-at :emailQueued email-queued))))

(defn staff-link!
  "Issues the invitation (pending member) or reset link for `user`; returns `{:kind :url :expiresAt :emailQueued}`."
  [deps user]
  (let [{:keys [kind url expires-at email-queued]} (send-password-link! deps user)]
    {:kind kind :url url :expiresAt expires-at :emailQueued email-queued}))

(defn owner-link!
  "CLI: issues a one-time password reset link for the owner account. `deps` has `:conn :config :pepper :now`.
  Returns the link map, or nil when no owner exists."
  [{:keys [conn] :as deps}]
  (when-let [owner (first (filter #(= :owner (:role %)) (store/list-users conn)))]
    (send-password-link! deps (assoc owner :invitationPending false))))

(defn forgot-password!
  "Sends a reset link when `email` belongs to an active user; does nothing otherwise (callers always answer 202)."
  [{:keys [conn] :as deps} email]
  (when-let [user (store/user-by-email conn (.toLowerCase ^String email))]
    (when (:active user) (send-password-link! deps user))))

(defn purge-expired!
  "Housekeeping: removes sessions and tokens that expired more than a day before `now`."
  [conn now]
  (store/purge-expired! conn (- (t/epoch-ms now) 86400000)))
