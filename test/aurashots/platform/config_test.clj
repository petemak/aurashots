(ns aurashots.platform.config-test
  "Tests of the `config` platform component (fitness check F46, runtime part): `PUBLIC_BASE_URL` is required when
  `APP_ENV` is staging or prod, and the localhost default exists for dev only."
  (:require [aurashots.platform.config :as config]
            [aurashots.test-helpers :as h]
            [clojure.test :refer [deftest is testing]]))

(deftest ^{:doc "Tests the public base url requirement."} public-base-url-is-required-outside-dev
  (let [base {:data-dir (h/temp-dir) :token-pepper "test-only-pepper-0123456789abcdef"}]
    (testing "dev keeps the localhost default"
      (is (= "http://localhost:8080" (:public-base-url (config/load-config base)))))
    (doseq [env [:staging :prod]]
      (testing (name env)
        (let [ex (try (config/load-config (assoc base :app-env env :public-base-url "")) nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (re-find #"PUBLIC_BASE_URL must be set" (str (ex-message ex)))))
        (is (= "https://x.example.test"
               (:public-base-url (config/load-config (assoc base :app-env env :public-base-url "https://x.example.test/")))))))))
