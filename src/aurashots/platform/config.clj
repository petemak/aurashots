(ns aurashots.platform.config
  "Platform component `config` (architecture.md section 2.2): reads resources/config.edn with Aero
  (profiles dev, staging, prod; secrets only via #env), derives paths, validates the whole map with Malli
  and fails fast. Needs no other component."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.security SecureRandom)
           (java.util Base64)))

(def Config
  "Malli schema of the merged configuration map."
  [:map
   [:app-env [:enum :dev :staging :prod]]
   [:http [:map [:host :string] [:port pos-int?] [:trusted-proxy-hops nat-int?] [:cookie-secure :boolean]]]
   [:public-base-url [:re #"^https?://.+"]]
   [:production-base-url [:maybe [:re #"^https?://.+"]]]
   [:data-dir :string]
   [:upload-dir :string]
   [:web-root :string]
   [:backup-dir :string]
   [:owner-email [:maybe :string]]
   [:smtp [:map [:host :string] [:port pos-int?] [:user [:maybe :string]] [:password [:maybe :string]]]]
   [:token-pepper [:string {:min 16}]]
   [:log-level [:enum :trace :debug :info :warn :error]]
   [:seed-images-dir :string]
   [:outbox-interval-ms pos-int?]
   [:housekeeping-interval-ms pos-int?]
   [:weekly-export [:map [:day [:enum :mon :tue :wed :thu :fri :sat :sun]] [:hour [:int {:min 0 :max 23}]] [:keep pos-int?]]]
   [:response-check [:enum :off :log :throw]]])

(defn- deep-merge
  "Recursively merges maps; non-map values in `b` win, and a nil `b` leaves `a` unchanged."
  [a b]
  (cond (nil? b) a
        (and (map? a) (map? b)) (merge-with deep-merge a b)
        :else b))

(defn- blank->nil
  "Returns nil for nil or blank strings, otherwise the string."
  [s]
  (when-not (str/blank? s) s))

(defn- dev-pepper
  "Returns the pepper kept in `<data-dir>/.dev-token-pepper`, creating a random one on first use.
  Only used outside staging and prod, so no secret is ever committed or hard-coded."
  [data-dir]
  (let [f (io/file data-dir ".dev-token-pepper")]
    (if (.exists f)
      (str/trim (slurp f))
      (let [bytes (byte-array 32)
            _ (.nextBytes (SecureRandom.) bytes)
            v (.encodeToString (Base64/getUrlEncoder) bytes)]
        (io/make-parents f)
        (spit f v)
        v))))

(defn load-config
  "Reads and validates the configuration. `overrides` (optional map) is deep-merged over the Aero result,
  used by tests for temp directories. Returns the config map; throws `ex-info` with humanized Malli
  errors (never the secret values) when it is invalid."
  ([] (load-config nil))
  ([overrides]
   (let [res (io/resource "config.edn")
         env (:app-env (aero/read-config res))
         raw (aero/read-config res {:profile env})
         merged (deep-merge raw overrides)
         data-dir (:data-dir merged)
         cfg (-> merged
                 (update :upload-dir #(or (blank->nil %) (str (io/file data-dir "uploads"))))
                 (update :backup-dir #(or (blank->nil %) (str (io/file data-dir "backups"))))
                 (update :owner-email blank->nil)
                 (update :production-base-url #(some-> (blank->nil %) (str/replace #"/+$" "")))
                 (update-in [:smtp :user] blank->nil)
                 (update-in [:smtp :password] blank->nil)
                 (update :public-base-url #(str/replace (str %) #"/+$" ""))
                 (update :token-pepper #(or (blank->nil %)
                                            (when (= :dev (:app-env merged)) (dev-pepper data-dir)))))]
     (when (and (#{:staging :prod} (:app-env merged)) (str/blank? (:public-base-url cfg)))
       (throw (ex-info (str "PUBLIC_BASE_URL must be set when APP_ENV is " (name (:app-env merged))
                            " (the https origin of the site, used for canonical URLs, emails and the sitemap)")
                       {:missing "PUBLIC_BASE_URL"})))
     (when-not (m/validate Config cfg)
       (throw (ex-info "Invalid configuration"
                       {:errors (me/humanize (m/explain Config (update cfg :token-pepper #(when % "<redacted>"))))})))
     cfg)))

(defmethod ig/init-key :aurashots/config [_ opts]
  (load-config (:overrides opts)))
