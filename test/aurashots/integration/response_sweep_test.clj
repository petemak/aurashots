(ns aurashots.integration.response-sweep-test
  "Exercises the routes that the other integration tests do not reach, so that every response schema is checked
  against a real response (the test system runs with `:response-check :throw`, which turns any response that does
  not match its schema into a 500 with the explanation). Also covers contract corner cases of the response shapes."
  (:require [aurashots.test-helpers :as h :refer [call uuid]]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def env "The running test system." (atom nil))

(use-fixtures :once
  (fn [f]
    (reset! env (h/start-system {:web-root (h/web-root-fixture)}))
    (try (f) (finally (h/stop-system @env)))))

(deftest ^{:doc "Tests the quote with an unknown service id."} quote-with-unknown-id
  (let [r (call @env :post "/api/quotes/sessions" {:body {:serviceIds ["no-such-service"]}})]
    (is (= 200 (:status r)))
    (is (= "removed" (get-in r [:body :items 0 :reason])))))

(deftest ^{:doc "Tests enquiry answered, change-password and health."} enquiry-password-health
  (let [e @env owner (h/owner-session e)
        support (h/support-session e owner "sweep-support@example.test")]
    (is (= 200 (:status (call e :get "/api/health"))))
    (is (= 201 (:status (call e :post "/api/enquiries" {:body {:name "Fay Test" :email "fay@example.test" :phone nil
                                                               :message "Do you shoot weddings?"}
                                                        :idem (uuid) :ip "81.0.0.1"}))))
    (let [id (get-in (call e :get "/api/admin/enquiries" {:session owner}) [:body :items 0 :id])
          r (call e :post (str "/api/admin/enquiries/" id "/answered") {:session owner})]
      (is (= 200 (:status r)))
      (is (= "answered" (get-in r [:body :status]))))
    (is (= 204 (:status (call e :post "/api/admin/auth/change-password"
                              {:session support :body {:currentPassword "another long password 12"
                                                       :newPassword "yet another long password 3"}}))))))

(deftest ^{:doc "Tests product admin routes and order notes."} product-and-order-routes
  (let [e @env owner (h/owner-session e)
        img (get-in (call e :get "/api/portfolio") [:body :items 0 :image :id])
        body {:name "Sweep Tee" :category "clothing" :description "Soft tee" :details "Cotton" :priceRwf 15000
              :optionGroups [{:name "Size" :options [{:label "M" :inStock true} {:label "L" :inStock true}]}]
              :images [{:imageId img :alt "Tee"}] :isNew true :featured false :status "published"}
        p (call e :post "/api/admin/products" {:session owner :body body})
        pid (get-in p [:body :id])]
    (is (= 201 (:status p)))
    (is (= 200 (:status (call e :get (str "/api/admin/products/" pid) {:session owner}))))
    (is (= 200 (:status (call e :put (str "/api/admin/products/" pid) {:session owner :body (assoc body :name "Sweep Tee 2")}))))
    (let [opt (get-in (call e :get (str "/api/admin/products/" pid) {:session owner}) [:body :optionGroups 0 :options 0 :id])
          r (call e :patch (str "/api/admin/products/" pid "/availability") {:session owner :body {:optionId opt :inStock false}})]
      (is (= 200 (:status r)))
      (is (= 200 (:status (call e :patch (str "/api/admin/products/" pid "/availability") {:session owner :body {:inStock true}})))))
    (testing "order detail, note and message resend"
      (let [pub (call e :get (str "/api/products/" (get-in p [:body :slug])))
            opts (mapv #(get-in % [:options 0 :id]) (get-in pub [:body :optionGroups]))
            o (call e :post "/api/merchandise-orders"
                    {:idem (uuid) :ip "81.0.0.2"
                     :body {:customer {:name "Gus Test" :phone "+250788555668" :email "gus@example.test"}
                            :delivery {:areaText "Remera" :address "KG 11 Ave"}
                            :items [{:productId pid :optionIds opts :quantity 1}]
                            :agreement {:slug "merchandise-order-terms" :version 1 :accepted true}}})
            id (get-in o [:body :id])
            detail (call e :get (str "/api/admin/merchandise-orders/" id) {:session owner})]
        (is (= 201 (:status o)))
        (is (= 200 (:status detail)))
        (is (= 201 (:status (call e :post (str "/api/admin/merchandise-orders/" id "/notes") {:session owner :body {:text "Call first"}}))))
        (let [mid (get-in detail [:body :messages 0 :id])]
          (is mid "the order has a queued message")
          (is (= 202 (:status (call e :post (str "/api/admin/merchandise-orders/" id "/messages/" mid "/resend") {:session owner})))))))
    (is (= 204 (:status (call e :delete (str "/api/admin/products/" pid) {:session owner}))))))

(deftest ^{:doc "Tests portfolio update and ordering."} portfolio-update-and-order
  (let [e @env owner (h/owner-session e)
        items (get-in (call e :get "/api/admin/portfolio" {:session owner}) [:body :items])
        it (first items)
        r (call e :put (str "/api/admin/portfolio/" (:id it))
                {:session owner :body {:imageId (get-in it [:image :id]) :caption "Edited" :description "Edited description"
                                       :categoryId (:categoryId it) :featured (:featured it)}})]
    (is (= 200 (:status r)))
    (is (= 204 (:status (call e :put "/api/admin/portfolio-order" {:session owner :body {:ids (vec (reverse (map :id items)))}}))))))

(deftest ^{:doc "Tests static page routes."} static-pages
  (doseq [p ["/cart/sessions" "/cart/merchandise" "/shop/checkout" "/shop/received/AS-M-X" "/book/received/AS-S-X"
             "/portfolio" "/legal/terms-of-sale" "/legal/order-terms" "/js/none.js" "/fonts/none.woff2" "/assets/none.png"]]
    (is (contains? #{200 404} (:status (call @env :get p))) p)))
