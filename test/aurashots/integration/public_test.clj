(ns aurashots.integration.public-test
  "Integration tests of the public API through the real route table (api-contract.md section 2): catalogue reads,
  caching headers, availability, quotes, booking requests with idempotency and agreement checks, merchandise
  orders, enquiries, honeypot, rate limiting and customer status lookups."
  (:require [aurashots.test-helpers :as h :refer [call uuid]]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def env "The running test system." (atom nil))

(use-fixtures :once
  (fn [f]
    (reset! env (h/start-system))
    (try (f) (finally (h/stop-system @env)))))

(deftest ^{:doc "Tests catalogue reads."} catalogue-reads
  (let [r (call @env :get "/api/services")]
    (is (= 200 (:status r)))
    (is (= ["Single Session" "Couple Session" "Besties Package" "Special Package" "Weekend Sessions"]
           (map :name (get-in r [:body :items]))))
    (is (= [50000 100000 12000 10000 15000] (map :priceRwf (get-in r [:body :items]))))
    (is (= "public, max-age=60, stale-while-revalidate=300" (get-in r [:headers "Cache-Control"])))
    (testing "ETag gives 304"
      (let [etag (get-in r [:headers "ETag"])
            r2 (call @env :get "/api/services" {:headers {"if-none-match" etag}})]
        (is (some? etag))
        (is (= 304 (:status r2))))))
  (let [r (call @env :get "/api/services/single-session")]
    (is (= 200 (:status r)))
    (is (= "/media/seed-photo-1/large.jpg" (get-in r [:body :mainImage :url])))
    (is (vector? (get-in r [:body :relatedServices]))))
  (is (= 404 (:status (call @env :get "/api/services/nope"))))
  (let [r (call @env :get "/api/portfolio?pageSize=4")]
    (is (= 6 (get-in r [:body :total])))
    (is (= 4 (count (get-in r [:body :items])))))
  (is (= 3 (count (get-in (call @env :get "/api/portfolio?featured=true") [:body :items]))))
  (let [r (call @env :get "/api/site-info")]
    (is (= 60 (get-in r [:body :booking :defaultSessionMinutes])))
    (is (= "OWNER_EMAIL" (get-in r [:body :email])) "placeholder while email is unset"))
  (is (= 200 (:status (call @env :get "/api/documents/session-booking-agreement"))))
  (is (= 404 (:status (call @env :get "/api/documents/unknown"))))
  (is (= 200 (:status (call @env :get "/api/documents/privacy?version=1")))))

(deftest ^{:doc "Tests availability endpoint."} availability-endpoint
  (let [r (call @env :get "/api/availability?from=2026-11-02&to=2026-11-03")]
    (is (= 200 (:status r)))
    (is (= "no-store" (get-in r [:headers "Cache-Control"])))
    (is (seq (:slots (first (get-in r [:body :days]))))))
  (let [r (call @env :get "/api/availability?from=2026-11-02&to=2026-12-20&date=2026-11-05")
        days (get-in r [:body :days])]
    (is (= 200 (:status r)))
    (is (nil? (:slots (first days))) "span over 7 days: slots only for the requested date")
    (is (seq (:slots (first (filter #(= "2026-11-05" (:date %)) days))))))
  (is (= 400 (:status (call @env :get "/api/availability?from=2026-11-02&to=2027-03-01"))) "over 62 days")
  (testing "longer session shortens the last start"
    (let [r (call @env :get "/api/availability?from=2026-11-02&to=2026-11-02&serviceIds=seed-service-single-session,seed-service-couple-session")]
      (is (= "20:00" (:startTime (last (:slots (first (get-in r [:body :days]))))))))))

(deftest ^{:doc "Tests session quote."} session-quote
  (let [r (call @env :post "/api/quotes/sessions" {:body {:serviceIds ["seed-service-single-session" "seed-service-couple-session" "ghost"]}})]
    (is (= 150000 (get-in r [:body :totalRwf])))
    (is (= [true true false] (map :available (get-in r [:body :items]))))
    (is (= "removed" (:reason (nth (get-in r [:body :items]) 2))))))

(deftest ^{:doc "Tests booking request flow."} booking-request-flow
  (let [e @env k (uuid) b (h/booking-body)
        r1 (call e :post "/api/booking-requests" {:body b :idem k :ip "10.0.0.1"})]
    (is (= 201 (:status r1)))
    (is (re-matches #"AS-S-[A-HJ-NP-Z2-9]{8}" (get-in r1 [:body :reference])))
    (is (= 60 (get-in r1 [:body :durationMinutes])))
    (is (= "pending" (get-in r1 [:body :status])))
    (testing "same key and body replays the original response"
      (let [r2 (call e :post "/api/booking-requests" {:body b :idem k :ip "10.0.0.1"})]
        (is (= 201 (:status r2)))
        (is (= "true" (get-in r2 [:headers "Idempotent-Replay"])))
        (is (= (:body r1) (:body r2)))))
    (testing "same key with a different body is rejected"
      (let [r3 (call e :post "/api/booking-requests" {:body (assoc-in b [:customer :name] "Bob Test") :idem k :ip "10.0.0.1"})]
        (is (= 422 (:status r3)))
        (is (= "idempotencyKeyReused" (get-in r3 [:body :error :code])))))
    (testing "customer lookup with token"
      (let [ref (get-in r1 [:body :reference]) tok (get-in r1 [:body :accessToken])
            ok (call e :get (str "/api/booking-requests/" ref "?token=" tok))
            bad (call e :get (str "/api/booking-requests/" ref "?token=wrong"))]
        (is (= 200 (:status ok)))
        (is (= "pending" (get-in ok [:body :status])))
        (is (= 404 (:status bad)))
        (is (= "notFound" (get-in bad [:body :error :code])))))))

(deftest ^{:doc "Tests booking validation and business rules."} booking-validation-and-business-rules
  (let [e @env post (fn [b & [ip]] (call e :post "/api/booking-requests" {:body b :idem (uuid) :ip (or ip "10.0.0.2")}))]
    (testing "validation errors carry fields"
      (let [r (post (assoc-in (h/booking-body) [:customer :email] "nope"))]
        (is (= 400 (:status r)))
        (is (= "validationFailed" (get-in r [:body :error :code])))
        (is (contains? (get-in r [:body :error :fields]) :customer.email))))
    (testing "unknown keys are rejected"
      (is (= 400 (:status (post (assoc (h/booking-body) :extra 1))))))
    (testing "agreement must be accepted and current"
      (is (= 400 (:status (post (assoc-in (h/booking-body) [:agreement :accepted] false)))))
      (let [r (post (assoc-in (h/booking-body) [:agreement :version] 99))]
        (is (= 409 (:status r)))
        (is (= "agreementVersionStale" (get-in r [:body :error :code])))))
    (testing "past, too soon, outside hours and off-grid slots"
      (doseq [slot [{:date "2026-10-01" :startTime "10:00"} {:date "2026-10-29" :startTime "20:00"}
                    {:date "2026-11-20" :startTime "23:00"} {:date "2026-11-20" :startTime "10:15"}]]
        (let [r (post (assoc (h/booking-body) :preferredSlot slot))]
          (is (= 409 (:status r)) (str slot))
          (is (= "slotUnavailable" (get-in r [:body :error :code]))))))
    (testing "unknown service"
      (let [r (post (assoc (h/booking-body) :serviceIds ["ghost"]))]
        (is (= "itemUnavailable" (get-in r [:body :error :code])))))
    (testing "honeypot gives a generic 400 and stores nothing"
      (let [r (post (assoc (h/booking-body) :website "http://spam"))]
        (is (= 400 (:status r)))
        (is (nil? (get-in r [:body :error :fields])))))
    (testing "idempotency key is mandatory"
      (is (= 400 (:status (call e :post "/api/booking-requests" {:body (h/booking-body) :ip "10.0.0.3"})))))))

(deftest ^{:doc "Tests enquiry and rate limit."} enquiry-and-rate-limit
  (let [e @env body {:name "Eve Test" :email "eve@example.test" :phone nil :message "Hello, do you do birthdays?" :website ""}
        k (uuid)]
    (is (= 201 (:status (call e :post "/api/enquiries" {:body body :idem k :ip "10.9.9.9"}))))
    (is (= "true" (get-in (call e :post "/api/enquiries" {:body body :idem k :ip "10.9.9.9"}) [:headers "Idempotent-Replay"])))
    (testing "the 11th public POST in an hour from one address gets 429"
      (let [codes (doall (for [_ (range 10)] (:status (call e :post "/api/enquiries" {:body body :idem (uuid) :ip "10.9.9.9"}))))]
        (is (= 429 (last codes)))
        (let [r (call e :post "/api/enquiries" {:body body :idem (uuid) :ip "10.9.9.9"})]
          (is (= "rateLimited" (get-in r [:body :error :code])))
          (is (some? (get-in r [:headers "Retry-After"]))))))
    (testing "another address is unaffected"
      (is (= 201 (:status (call e :post "/api/enquiries" {:body body :idem (uuid) :ip "10.9.9.10"})))))))

(deftest ^{:doc "Tests concurrent duplicate submissions."} concurrent-duplicate-submissions
  (let [e @env k (uuid) b (h/booking-body {:customer {:name "Cara Test" :phone "+250 788 333 444" :email "cara@example.test"}})
        rs (doall (pmap (fn [_] (call e :post "/api/booking-requests" {:body b :idem k :ip "10.0.0.7"})) (range 4)))
        statuses (frequencies (map :status rs))
        refs (set (keep #(get-in % [:body :reference]) (filter #(= 201 (:status %)) rs)))]
    (is (every? #{201 409} (keys statuses)))
    (is (= 1 (count refs)) "exactly one booking is ever created")))

(deftest ^{:doc "Tests merchandise order flow."} merchandise-order-flow
  (let [e @env
        owner (h/owner-session e)
        img (let [r (call e :get "/api/portfolio")] (get-in r [:body :items 0 :image :id]))
        p (call e :post "/api/admin/products"
                {:session owner
                 :body {:name "Aura Tee" :category "clothing" :description "Soft cotton" :details "Unisex" :priceRwf 15000
                        :optionGroups [{:name "Size" :options [{:label "M" :inStock true} {:label "L" :inStock false}]}]
                        :images [{:imageId img :alt "Tee"}] :isNew true :featured false :status "published"}})
        pid (get-in p [:body :id])
        [m l] (map :id (get-in p [:body :optionGroups 0 :options]))
        order (fn [opts items & [k]]
                (call e :post "/api/merchandise-orders"
                      {:idem (or k (uuid)) :ip "10.5.5.5"
                       :body {:customer {:name "Dan Test" :phone "+250788555666" :email "dan@example.test"}
                              :delivery (merge {:areaId "kigali-kanombe" :address "KG 1 Ave"} opts)
                              :items items
                              :agreement {:slug "merchandise-order-terms" :version 1 :accepted true} :website ""}}))]
    (is (= 201 (:status p)))
    (testing "listing and detail"
      (let [r (call e :get "/api/products?category=clothing")]
        (is (= "inStock" (get-in r [:body :items 0 :availability])))
        (is (= 15000 (get-in r [:body :items 0 :priceRwf]))))
      (let [r (call e :get "/api/products/aura-tee")]
        (is (= 200 (:status r)))
        (is (= [] (get-in r [:body :relatedServiceIds])))))
    (testing "quote returns lines in request order and fee note"
      (let [r (call e :post "/api/quotes/merchandise"
                    {:body {:items [{:productId pid :optionIds [l] :quantity 1} {:productId pid :optionIds [m] :quantity 2}]
                            :deliveryAreaId "kigali-kanombe"}})]
        (is (= [false true] (map :available (get-in r [:body :items]))))
        (is (= "soldOut" (get-in r [:body :items 0 :reason])))
        (is (= 30000 (get-in r [:body :subtotalRwf])))
        (is (nil? (get-in r [:body :totalRwf])))
        (is (= "toBeConfirmed" (get-in r [:body :feeNote])))))
    (testing "sold-out option is refused"
      (is (= "itemUnavailable" (get-in (order {} [{:productId pid :optionIds [l] :quantity 1}]) [:body :error :code]))))
    (testing "either areaId or areaText is required"
      (is (= 400 (:status (order {:areaId nil} [{:productId pid :optionIds [m] :quantity 1}])))))
    (testing "successful order with snapshot, replay and status lookup"
      (let [k (uuid)
            r (order {} [{:productId pid :optionIds [m] :quantity 2}] k)
            r2 (order {} [{:productId pid :optionIds [m] :quantity 2}] k)]
        (is (= 201 (:status r)))
        (is (re-matches #"AS-M-[A-HJ-NP-Z2-9]{8}" (get-in r [:body :reference])))
        (is (= 30000 (get-in r [:body :subtotalRwf])))
        (is (nil? (get-in r [:body :deliveryFeeRwf])))
        (is (= (:body r) (:body r2)))
        (let [g (call e :get (str "/api/merchandise-orders/" (get-in r [:body :reference]) "?token=" (get-in r [:body :accessToken])))]
          (is (= 200 (:status g)))
          (is (= "pending" (get-in g [:body :status]))))))))
