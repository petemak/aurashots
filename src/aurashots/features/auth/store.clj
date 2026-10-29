(ns aurashots.features.auth.store
  "Slice `auth`, data access (architecture.md section 3.2): `user` entities (`:user/id :email :role` and the record in
  `:user/data`), server-side `session` entities (hashed id, user id, expiry) and single-use `passwordToken`
  entities (`:ptoken/*`). Secrets are stored only as keyed hashes."
  (:require [datalevin.core :as d]))

(defn transact!
  "Transacts raw `tx-data` (for example queued messages) on `conn`."
  [conn tx-data]
  (d/transact! conn tx-data))

(defn save-user!
  "Inserts or updates user record `u`. Side effect: one transaction."
  [conn u]
  (d/transact! conn [{:user/id (:id u) :user/email (:email u) :user/role (:role u) :user/data u}])
  u)

(defn user-by-id
  "Returns the user record with `id`, or nil."
  [conn id]
  (:user/data (d/pull (d/db conn) [:user/data] [:user/id id])))

(defn user-by-email
  "Returns the user record for lower-case `email`, or nil."
  [conn email]
  (:user/data (d/pull (d/db conn) [:user/data] [:user/email email])))

(defn list-users
  "Returns all user records ordered with the owner first, then by name."
  [conn]
  (->> (d/q '[:find [(pull ?e [:user/data]) ...] :where [?e :user/id _]] (d/db conn))
       (map :user/data) (sort-by (juxt #(if (= :owner (:role %)) 0 1) :name)) vec))

(defn owner-exists?
  "True when an owner account exists."
  [conn]
  (boolean (d/q '[:find ?e . :where [?e :user/role :owner]] (d/db conn))))

(defn delete-user!
  "Deletes the user, all their sessions and tokens. Side effect: one transaction."
  [conn id]
  (let [db (d/db conn)
        sessions (d/q '[:find [?e ...] :in $ ?u :where [?e :session/userId ?u]] db id)
        tokens (d/q '[:find [?e ...] :in $ ?u :where [?e :ptoken/userId ?u]] db id)]
    (d/transact! conn (-> (mapv (fn [e] [:db/retractEntity e]) (concat sessions tokens))
                          (conj [:db/retractEntity [:user/id id]])))))

(defn save-session!
  "Stores a session `{:hash :user-id :now :expires-at}` (epoch-ms values). Side effect: one transaction."
  [conn {:keys [hash user-id now expires-at]}]
  (d/transact! conn [{:session/hash hash :session/userId user-id :session/createdAt now
                      :session/lastSeenAt now :session/expiresAt expires-at}]))

(defn get-session
  "Returns `{:hash :userId :createdAt :lastSeenAt :expiresAt}` for the session `hash`, or nil."
  [conn hash]
  (let [e (d/pull (d/db conn) '[*] [:session/hash hash])]
    (when (:db/id e)
      {:hash hash :userId (:session/userId e) :createdAt (:session/createdAt e)
       :lastSeenAt (:session/lastSeenAt e) :expiresAt (:session/expiresAt e)})))

(defn touch-session!
  "Moves the session's last-seen time to `now` and its expiry to `expires-at` (epoch-ms)."
  [conn hash now expires-at]
  (d/transact! conn [{:session/hash hash :session/lastSeenAt now :session/expiresAt expires-at}]))

(defn delete-session!
  "Deletes the session with `hash`."
  [conn hash]
  (when (d/q '[:find ?e . :in $ ?h :where [?e :session/hash ?h]] (d/db conn) hash)
    (d/transact! conn [[:db/retractEntity [:session/hash hash]]])))

(defn delete-user-sessions!
  "Ends every session of user `user-id`."
  [conn user-id]
  (let [es (d/q '[:find [?e ...] :in $ ?u :where [?e :session/userId ?u]] (d/db conn) user-id)]
    (when (seq es) (d/transact! conn (mapv (fn [e] [:db/retractEntity e]) es)))))

(defn save-token!
  "Stores a password token `{:hash :user-id :expires-at}` (epoch-ms). Side effect: one transaction."
  [conn {:keys [hash user-id expires-at]}]
  (d/transact! conn [{:ptoken/hash hash :ptoken/userId user-id :ptoken/expiresAt expires-at :ptoken/used false}]))

(defn get-token
  "Returns `{:userId :expiresAt :used}` for the token `hash`, or nil."
  [conn hash]
  (let [e (d/pull (d/db conn) '[*] [:ptoken/hash hash])]
    (when (:db/id e)
      {:userId (:ptoken/userId e) :expiresAt (:ptoken/expiresAt e) :used (:ptoken/used e)})))

(defn use-token!
  "Marks the token `hash` as used."
  [conn hash]
  (d/transact! conn [{:ptoken/hash hash :ptoken/used true}]))

(defn purge-expired!
  "Deletes sessions and tokens that expired before epoch-ms `cutoff`; returns how many were removed."
  [conn cutoff]
  (let [db (d/db conn)
        es (concat (d/q '[:find [?e ...] :in $ ?c :where [?e :session/expiresAt ?t] [(< ?t ?c)]] db cutoff)
                   (d/q '[:find [?e ...] :in $ ?c :where [?e :ptoken/expiresAt ?t] [(< ?t ?c)]] db cutoff))]
    (when (seq es) (d/transact! conn (mapv (fn [e] [:db/retractEntity e]) es)))
    (count es)))
