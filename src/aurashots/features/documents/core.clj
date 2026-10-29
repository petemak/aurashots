(ns aurashots.features.documents.core
  "Slice `documents`, pure logic (architecture.md section 2.3): document and version views, body hashing, agreement
  acceptance checks and the immutable acceptance record. No I/O."
  (:require [aurashots.platform.crypto :as crypto]))

(defn body-hash
  "SHA-256 hex of the document `body` text; recorded with every acceptance as proof of the exact wording."
  [body]
  (crypto/sha256-hex body))

(defn public-view
  "Builds the public document body of api-contract.md section 2.10 from the document header `doc` and version `ver`."
  [doc ver]
  {:slug (:slug doc) :title (:title doc) :version (:version ver) :publishedAt (:publishedAt ver)
   :requiresAcceptance (boolean (:requiresAcceptance doc)) :summaryPoints (:summaryPoints ver) :body (:body ver)})

(defn admin-list-item
  "Builds one row of the admin document list from the header `doc` and its current version `ver`."
  [doc ver]
  {:slug (:slug doc) :title (:title doc) :currentVersion (:version ver) :publishedAt (:publishedAt ver)
   :reviewStatus (name (:reviewStatus doc)) :requiresAcceptance (boolean (:requiresAcceptance doc))})

(defn admin-view
  "Builds the admin document body: the current text plus `versions` (newest first) from `all-versions`."
  [doc ver all-versions]
  (assoc (public-view doc ver)
         :reviewStatus (name (:reviewStatus doc))
         :versions (->> all-versions (sort-by :version >)
                        (mapv #(select-keys % [:version :publishedAt :publishedByName :changeNote])))))

(defn new-version
  "Builds the next immutable version record after `current` from `{:body :summaryPoints :changeNote}`."
  [slug current {:keys [body summaryPoints changeNote]} published-by now]
  {:slug slug :version (inc (:version current)) :body body :summaryPoints (vec summaryPoints)
   :publishedAt now :publishedByName published-by :changeNote changeNote :bodyHash (body-hash body)})

(defn agreement-problem
  "Checks the submitted `agreement` `{:slug :version :accepted}` against `expected-slug` and the `current` version
  record. Returns nil when acceptable, otherwise `{:code kw :message s :fields m}` for the caller to throw:
  `validationFailed` for a missing acceptance or wrong document, `agreementVersionStale` when the version differs."
  [expected-slug agreement current]
  (cond
    (not= expected-slug (:slug agreement))
    {:code :validationFailed :message "That is not the right agreement for this request."
     :fields {"agreement.slug" (str "must be " expected-slug)}}

    (not (true? (:accepted agreement)))
    {:code :validationFailed :message "Please accept the agreement to continue."
     :fields {"agreement.accepted" "must be accepted"}}

    (not= (:version agreement) (:version current))
    {:code :agreementVersionStale
     :message (str "The agreement has changed. The current version is " (:version current) ".")
     :fields {"currentVersion" (str (:version current))}}))

(defn acceptance-record
  "Builds the immutable acceptance record (api-contract.md section 2.11 step 4). `ver` is the accepted version
  record; `params` carries `:id :subject-type :subject-id :subject-reference :customer {:name :email :phone}
  :now :ip :user-agent`."
  [ver {:keys [id subject-type subject-id subject-reference customer now ip user-agent]}]
  {:id id :documentSlug (:slug ver) :version (:version ver) :bodyHash (:bodyHash ver)
   :subjectType subject-type :subjectId subject-id :subjectReference subject-reference
   :customerName (:name customer) :customerEmail (:email customer) :customerPhone (:phone customer)
   :acceptedAt now :ipAddress ip :userAgent user-agent})

(defn acceptance-summary
  "The short form of an acceptance returned to customers: `{slug version acceptedAt}`."
  [acceptance]
  {:slug (:documentSlug acceptance) :version (:version acceptance) :acceptedAt (:acceptedAt acceptance)})
