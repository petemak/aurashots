(ns aurashots.features.auth.core
  "Slice `auth`, pure logic (architecture.md section 2.3): user records, views, session lifetimes and the session
  cookie header. No I/O; time and ids are passed in."
  (:import (java.time Duration Instant)))

(def session-idle
  "A session stays valid this long after its last use."
  (Duration/ofHours 12))

(def session-max
  "A session never lives longer than this, however often it is used."
  (Duration/ofDays 7))

(def reset-token-ttl
  "Password reset links are valid for 60 minutes (api-contract.md section 3.1)."
  (Duration/ofMinutes 60))

(def invitation-token-ttl
  "Staff invitation links are valid longer than resets, because a new member may not read mail at once."
  (Duration/ofHours 72))

(defn new-user
  "Builds a user record. `role` is :owner or :support. The user has no password until a token is redeemed."
  [{:keys [id name email role]} now]
  {:id id :name name :email email :role role :active true :passwordHash nil
   :invitationPending true :lastSignInAt nil :createdAt now})

(defn session-user
  "The user map exposed to handlers and the login response: `{:id :name :email :role}` (role as a string for JSON)."
  [user]
  {:id (:id user) :name (:name user) :email (:email user) :role (name (:role user))})

(defn handler-user
  "The user map put on the request for authorisation: like `session-user` but with the role as a keyword."
  [user]
  (-> (session-user user) (assoc :role (:role user))))

(defn staff-view
  "The `GET /api/admin/staff` row for `user`."
  [user]
  {:id (:id user) :name (:name user) :email (:email user) :role (name (:role user)) :active (boolean (:active user))
   :invitationPending (boolean (:invitationPending user)) :lastSignInAt (:lastSignInAt user)})

(defn session-valid?
  "True when a session `{:expiresAt ms :createdAt ms}` is still usable at epoch-ms `now`."
  [{:keys [expiresAt createdAt]} now]
  (and (> expiresAt now) (< now (+ createdAt (.toMillis ^Duration session-max)))))

(defn new-expiry
  "Epoch-ms expiry for a session used at epoch-ms `now`."
  [now]
  (+ now (.toMillis ^Duration session-idle)))

(defn session-cookie
  "Builds the `Set-Cookie` value for the session `raw` id. `secure?` adds the Secure flag; `max-age` is in
  seconds (0 clears the cookie)."
  [raw secure? max-age]
  (str "session=" raw "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" max-age (when secure? "; Secure")))

(defn expires-text
  "Human readable expiry (`yyyy-MM-dd HH:mm` UTC) for emails."
  [^Instant instant]
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm 'UTC'")
           (.atOffset instant java.time.ZoneOffset/UTC)))
