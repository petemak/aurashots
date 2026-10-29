(ns aurashots.integration.admin-test
  "Integration tests of the admin API (api-contract.md section 3): authentication, CSRF, roles, the session request
  and order state machines (including idempotent repeats and the confirm race), catalogue, portfolio, calendar,
  settings, staff, documents and the outbox."
  (:require [aurashots.features.notifications.api :as notifications]
            [aurashots.test-helpers :as h :refer [call uuid]]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (javax.imageio ImageIO)))

(def env "The running test system." (atom nil))
(def owner "The owner session." (atom nil))
(def support "The support staff session." (atom nil))

(use-fixtures :once
  (fn [f]
    (reset! env (h/start-system))
    (reset! owner (h/owner-session @env))
    (reset! support (h/support-session @env @owner))
    (try (f) (finally (h/stop-system @env)))))

(defn submit!
  "Submits a booking request as a customer, accepting the agreement version that is current at the time."
  [ip slot & [overrides]]
  (let [v (get-in (call @env :get "/api/documents/session-booking-agreement") [:body :version])]
    (call @env :post "/api/booking-requests"
          {:body (h/booking-body (merge {:preferredSlot slot :agreement {:slug "session-booking-agreement" :version v :accepted true}}
                                        overrides))
           :idem (uuid) :ip ip})))

(deftest ^{:doc "Tests authentication."} authentication
  (let [e @env]
    (testing "unauthenticated and bad credentials"
      (is (= 401 (:status (call e :get "/api/admin/dashboard"))))
      (let [a (call e :post "/api/admin/auth/login" {:body {:email "owner@example.test" :password "wrong password!!"} :ip "1.1.1.1"})
            b (call e :post "/api/admin/auth/login" {:body {:email "nobody@example.test" :password "wrong password!!"} :ip "1.1.1.2"})]
        (is (= 401 (:status a)))
        (is (= (:body a) (:body b)) "same message for unknown email and wrong password")))
    (testing "cookie flags and me"
      (let [r (call e :post "/api/admin/auth/login" {:body {:email "owner@example.test" :password "correct horse battery staple"}})
            cookie (get-in r [:headers "Set-Cookie"])]
        (is (re-find #"HttpOnly" cookie))
        (is (re-find #"SameSite=Lax" cookie))
        (let [me (call e :get "/api/admin/auth/me" {:session @owner})]
          (is (= "owner" (get-in me [:body :user :role])))
          (is (= (:csrf @owner) (get-in me [:body :csrfToken]))))))
    (testing "csrf is required on state-changing calls"
      (is (= 403 (:status (call e :post "/api/admin/calendar/blocks"
                                {:headers {"cookie" (str "session=" (:cookie @owner))}
                                 :body {:fromDate "2027-01-05"}})))))
    (testing "five failures lock the email and address for a while"
      (let [codes (doall (for [_ (range 6)]
                           (:status (call e :post "/api/admin/auth/login"
                                          {:body {:email "owner@example.test" :password "wrong password!!"} :ip "9.9.9.9"}))))]
        (is (= [401 401 401 401 401 429] codes))))
    (testing "change password checks the current one"
      (is (= 400 (:status (call e :post "/api/admin/auth/change-password"
                                {:session @owner :body {:currentPassword "bad" :newPassword "a brand new password 1"}})))))
    (testing "forgot password always answers 202"
      (is (= 202 (:status (call e :post "/api/admin/auth/forgot-password" {:body {:email "nobody@example.test"} :ip "2.2.2.2"}))))
      (is (= 202 (:status (call e :post "/api/admin/auth/forgot-password" {:body {:email "owner@example.test"} :ip "2.2.2.3"})))))
    (testing "logout ends the session"
      (let [s (h/create-user! e {:email "owner@example.test" :password "correct horse battery staple"})]
        (is (= 204 (:status (call e :post "/api/admin/auth/logout" {:session s}))))
        (is (= 401 (:status (call e :get "/api/admin/auth/me" {:session s}))))))))

(deftest ^{:doc "Tests roles are enforced server side."} roles-are-enforced-server-side
  (let [e @env]
    (doseq [[m p] [[:get "/api/admin/staff"] [:get "/api/admin/documents"] [:get "/api/admin/acceptances"]]]
      (is (= 403 (:status (call e m p {:session @support}))) p)
      (is (= 200 (:status (call e m p {:session @owner}))) p))
    (is (= 403 (:status (call e :post "/api/admin/staff" {:session @support :body {:name "X Y" :email "x@example.test"}}))))
    (is (= 403 (:status (call e :post "/api/admin/documents/privacy/mark-reviewed" {:session @support}))))
    (is (= 200 (:status (call e :get "/api/admin/dashboard" {:session @support}))))
    (testing "there is no endpoint to create bookings for customers"
      (is (= 404 (:status (call e :post "/api/admin/booking-requests" {:session @owner :body {}})))))))

(deftest ^{:doc "Tests confirm lifecycle and idempotency."} confirm-lifecycle-and-idempotency
  (let [e @env
        r (submit! "20.0.0.1" {:date "2026-11-23" :startTime "10:00"})
        r2 (submit! "20.0.0.2" {:date "2026-11-23" :startTime "10:30"}) ; both pending: pending requests never block
        id (get-in r [:body :id])
        slot {:date "2026-11-23" :startTime "10:00"}
        confirm #(call e :post (str "/api/admin/booking-requests/" id "/confirm") {:session @owner :body (merge slot %)})]
    (is (= 201 (:status r)))
    (testing "detail shows agreement, history and queued messages (email unconfigured is fine)"
      (let [d (call e :get (str "/api/admin/booking-requests/" id) {:session @owner})]
        (is (= "pending" (get-in d [:body :status])))
        (is (re-find #"wa.me/250788111222" (get-in d [:body :whatsappLink])))
        (is (= 1 (get-in d [:body :agreementAcceptance :version])))
        (is (= 64 (count (get-in d [:body :agreementAcceptance :bodyHash]))))
        (is (= [["bookingReceived" "queued"]] (map (juxt :template :status) (get-in d [:body :messages])))
            "customer receipt queued; the owner alert is skipped while no owner address is configured")))
    (testing "confirm, then repeat is 200 with no second history entry"
      (let [c1 (confirm {:noteToCustomer "See you"})
            n1 (count (get-in c1 [:body :history]))
            c2 (confirm {:noteToCustomer "See you"})]
        (is (= 200 (:status c1)))
        (is (= "confirmed" (get-in c1 [:body :status])))
        (is (= 200 (:status c2)))
        (is (= n1 (count (get-in c2 [:body :history]))))
        (is (= 409 (:status (confirm {:startTime "11:00"}))) "a different slot is a conflict on confirm")))
    (testing "availability now excludes the confirmed session; pending requests do not block"
      (let [r (call e :get "/api/availability?from=2026-11-23&to=2026-11-23")
            av (fn [t] (:available (first (filter #(= t (:startTime %)) (:slots (first (get-in r [:body :days])))))))]
        (is (false? (av "10:00")))
        (is (false? (av "10:30")))
        (is (true? (av "11:00")))))
    (testing "another request cannot be confirmed over it without force; force records the override"
      (let [id2 (get-in r2 [:body :id])
            go (fn [force] (call e :post (str "/api/admin/booking-requests/" id2 "/confirm")
                                 {:session @support :body {:date "2026-11-23" :startTime "10:30" :force force}}))]
        (is (= "alreadyConfirmed" (get-in (call e :get (str "/api/admin/booking-requests/" id2) {:session @owner}) [:body :slotConflict])))
        (let [x (go false)]
          (is (= 409 (:status x)))
          (is (= "slotUnavailable" (get-in x [:body :error :code]))))
        (let [y (go true)]
          (is (= 200 (:status y)))
          (is (some #(re-find #"override" (str (:detail %))) (get-in y [:body :history]))))))
    (testing "reschedule, payment and completion"
      (let [rs (call e :post (str "/api/admin/booking-requests/" id "/reschedule")
                     {:session @owner :body {:date "2026-11-24" :startTime "14:00"}})
            p1 (call e :post (str "/api/admin/booking-requests/" id "/mark-paid") {:session @owner :body {:method "cash" :amountRwf 50000}})
            p2 (call e :post (str "/api/admin/booking-requests/" id "/mark-paid") {:session @owner :body {:method "cash" :amountRwf 50000}})
            p3 (call e :post (str "/api/admin/booking-requests/" id "/mark-paid") {:session @owner :body {:method "mobileMoney" :amountRwf 50000}})
            done (call e :post (str "/api/admin/booking-requests/" id "/complete") {:session @owner})
            again (call e :post (str "/api/admin/booking-requests/" id "/complete") {:session @owner})]
        (is (= "2026-11-24" (get-in rs [:body :confirmedSlot :date])))
        (is (= [200 200 409] (map :status [p1 p2 p3])))
        (is (= "completed" (get-in done [:body :status])))
        (is (= 200 (:status again)))
        (is (= 409 (:status (call e :post (str "/api/admin/booking-requests/" id "/reschedule")
                                  {:session @owner :body {:date "2026-11-25" :startTime "14:00"}}))) "completed is final")))
    (testing "notes"
      (let [n (call e :post (str "/api/admin/booking-requests/" id "/notes") {:session @support :body {:text "Called her"}})]
        (is (= 201 (:status n)))
        (is (= "Support Test" (get-in n [:body :authorName])))))
    (testing "decline"
      (let [r3 (submit! "20.0.0.3" {:date "2026-11-25" :startTime "09:00"})
            id3 (get-in r3 [:body :id])
            d (call e :post (str "/api/admin/booking-requests/" id3 "/decline") {:session @owner :body {:reason "Fully booked"}})
            d2 (call e :post (str "/api/admin/booking-requests/" id3 "/decline") {:session @owner :body {:reason "Fully booked"}})]
        (is (= "declined" (get-in d [:body :status])))
        (is (= 200 (:status d2)))
        (is (= 409 (:status (call e :post (str "/api/admin/booking-requests/" id3 "/confirm")
                                  {:session @owner :body {:date "2026-11-25" :startTime "09:00"}}))))))
    (testing "list and calendar views"
      (let [l (call e :get "/api/admin/booking-requests?status=completed" {:session @owner})
            c (call e :get "/api/admin/calendar?from=2026-11-01&to=2026-11-30" {:session @owner})]
        (is (pos? (get-in l [:body :total])))
        (is (vector? (get-in c [:body :sessions])))
        (is (vector? (get-in c [:body :pending])))))))

(deftest ^{:doc "Tests parallel confirm race."} parallel-confirm-race
  (let [e @env
        ids (mapv #(get-in (submit! (str "21.0.0." %) {:date "2026-12-01" :startTime "09:00"}) [:body :id]) (range 1 4))
        rs (doall (pmap (fn [id] (call e :post (str "/api/admin/booking-requests/" id "/confirm")
                                       {:session @owner :body {:date "2026-12-01" :startTime "09:00"}}))
                        ids))]
    (is (= [200 409 409] (sort (map :status rs))) "exactly one confirmation wins")
    (is (= #{"slotUnavailable"} (set (keep #(get-in % [:body :error :code]) rs))))))

(deftest ^{:doc "Tests services catalogue admin."} services-catalogue-admin
  (let [e @env
        sv (fn [& [m]] (merge {:name "Birthday Shoot" :shortDescription "Fun" :description "Long" :priceRwf nil :sessionMinutes 90
                               :contents {:editedPictures 10 :videos nil :outfits 2 :deliveryTime "1 week" :locations "Kigali"}
                               :conditions "No refunds" :relatedProductIds [] :images [] :featured false
                               :status "hidden" :sortOrder 5} m))]
    (testing "cannot publish without a price"
      (let [r (call e :post "/api/admin/services" {:session @owner :body (sv {:status "published"})})]
        (is (= 400 (:status r)))
        (is (contains? (get-in r [:body :error :fields]) :priceRwf))
        (is (= 400 (:status (call e :post "/api/admin/services" {:session @owner :body (sv {:status "published" :priceRwf 0})}))))))
    (let [c (call e :post "/api/admin/services" {:session @owner :body (sv)})
          id (get-in c [:body :id])]
      (is (= 201 (:status c)))
      (is (= "birthday-shoot" (get-in c [:body :slug])))
      (is (true? (get-in c [:body :needsPrice])))
      (testing "hidden services are not public; 404 on the detail route"
        (is (= 404 (:status (call e :get "/api/services/birthday-shoot")))))
      (testing "publish with a price"
        (let [u (call e :put (str "/api/admin/services/" id) {:session @support :body (sv {:status "published" :priceRwf 40000})})]
          (is (= 200 (:status u)))
          (is (= "birthday-shoot" (get-in u [:body :slug])) "slug is stable")
          (is (= 200 (:status (call e :get "/api/services/birthday-shoot"))))))
      (testing "price change does not alter an existing booking snapshot"
        (let [b (submit! "22.0.0.1" {:date "2026-12-03" :startTime "10:00"} {:serviceIds [id]})]
          (is (= 40000 (get-in b [:body :totalRwf])))
          (is (= 90 (get-in b [:body :durationMinutes])))
          (call e :put (str "/api/admin/services/" id) {:session @owner :body (sv {:status "published" :priceRwf 45000})})
          (let [d (call e :get (str "/api/admin/booking-requests/" (get-in b [:body :id])) {:session @owner})]
            (is (= 40000 (get-in d [:body :totalRwf]))))))
      (testing "soft remove"
        (is (= 204 (:status (call e :delete (str "/api/admin/services/" id) {:session @owner}))))
        (is (= 404 (:status (call e :get "/api/services/birthday-shoot"))))
        (is (= 409 (:status (submit! "22.0.0.2" {:date "2026-12-04" :startTime "10:00"} {:serviceIds [id]}))))))
    (testing "reserved slugs are skipped"
      (let [r (call e :post "/api/admin/services" {:session @owner :body (sv {:name "Checkout"})})]
        (is (= "checkout-2" (get-in r [:body :slug])))))
    (testing "ordering"
      (is (= 204 (:status (call e :put "/api/admin/service-order"
                                {:session @owner :body {:ids ["seed-service-weekend-sessions" "seed-service-single-session"]}}))))
      (is (= "Weekend Sessions" (get-in (call e :get "/api/services") [:body :items 0 :name]))))))

(defn png-bytes
  "PNG bytes of a blank `w` x `h` image."
  [w h]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_RGB) bos (ByteArrayOutputStream.)]
    (ImageIO/write img "png" bos)
    (.toByteArray bos)))

(defn multipart
  "Request options for a multipart upload of `content` named `filename`."
  [filename ^bytes content]
  (let [b "XBOUNDARYX"
        head (.getBytes (str "--" b "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" filename "\"\r\nContent-Type: image/png\r\n\r\n") "ISO-8859-1")
        tail (.getBytes (str "\r\n--" b "--\r\n") "ISO-8859-1")
        out (ByteArrayOutputStream.)]
    (.write out head) (.write out content) (.write out tail)
    {:headers {"content-type" (str "multipart/form-data; boundary=" b)} :body (ByteArrayInputStream. (.toByteArray out))}))

(deftest ^{:doc "Tests images and portfolio."} images-and-portfolio
  (let [e @env
        up (call e :post "/api/admin/images" (assoc (multipart "x.png" (png-bytes 2000 1200)) :session @owner))
        id (get-in up [:body :id])]
    (is (= 201 (:status up)))
    (is (= 1600 (get-in up [:body :width])))
    (is (= (str "/media/" id "/large.jpg") (get-in up [:body :url])))
    (testing "derivatives are served immutably"
      (doseq [s ["thumb" "card" "large"]]
        (let [r (call e :get (str "/media/" id "/" s ".jpg"))]
          (is (= 200 (:status r)) s)
          (is (= "public, max-age=31536000, immutable" (get-in r [:headers "Cache-Control"]))))))
    (testing "wrong file type is a 400"
      (is (= 400 (:status (call e :post "/api/admin/images" (assoc (multipart "x.jpg" (.getBytes "MZ not an image at all")) :session @owner))))))
    (testing "oversize pixel dimensions are a 400"
      (is (= 400 (:status (call e :post "/api/admin/images" (assoc (multipart "x.png" (png-bytes 6001 10)) :session @owner))))))
    (testing "portfolio item with the new image, then delete is blocked while in use"
      (let [p (call e :post "/api/admin/portfolio" {:session @support :body {:imageId id :caption "New" :description "A test picture" :categoryId "portraits" :featured false}})
            pid (get-in p [:body :id])]
        (is (= 201 (:status p)))
        (is (= "A test picture" (get-in p [:body :image :alt])))
        (is (= 409 (:status (call e :delete (str "/api/admin/images/" id) {:session @owner}))))
        (is (= 400 (:status (call e :post "/api/admin/portfolio" {:session @support :body {:imageId id :caption "x" :description "y" :categoryId "nope" :featured false}}))))
        (is (= 204 (:status (call e :delete (str "/api/admin/portfolio/" pid) {:session @support}))))
        (is (= 204 (:status (call e :delete (str "/api/admin/images/" id) {:session @owner}))))))))

(deftest ^{:doc "Tests calendar blocks."} calendar-blocks
  (let [e @env
        s (submit! "23.0.0.1" {:date "2026-12-10" :startTime "10:00"})
        _ (call e :post (str "/api/admin/booking-requests/" (get-in s [:body :id]) "/confirm")
                {:session @owner :body {:date "2026-12-10" :startTime "10:00"}})
        r (call e :post "/api/admin/calendar/blocks" {:session @support :body {:fromDate "2026-12-09" :toDate "2026-12-11" :reason "Holiday"}})]
    (is (= 201 (:status r)))
    (is (= 3 (count (get-in r [:body :items]))))
    (is (= 1 (count (get-in r [:body :affectedSessions]))) "warns instead of failing")
    (is (= "blocked" (get-in (call e :get "/api/availability?from=2026-12-10&to=2026-12-10") [:body :days 0 :status])))
    (is (= 400 (:status (call e :post "/api/admin/calendar/blocks" {:session @owner :body {:fromDate "2026-12-12" :startTime "10:00"}}))))
    (doseq [b (get-in r [:body :items])]
      (is (= 204 (:status (call e :delete (str "/api/admin/calendar/blocks/" (:id b)) {:session @owner})))))
    (is (= "available" (get-in (call e :get "/api/availability?from=2026-12-09&to=2026-12-09") [:body :days 0 :status])))))

(deftest ^{:doc "Tests settings and staff."} settings-and-staff
  (let [e @env
        cur (get-in (call e :get "/api/admin/settings" {:session @support}) [:body])
        put (fn [m] (call e :put "/api/admin/settings" {:session @support
                                                         :body (select-keys (merge cur m) [:phone :whatsapp :email :address :social :openingHours :booking :deliveryAreas :portfolioCategories])}))]
    (testing "the OWNER_EMAIL placeholder is accepted as-is"
      (is (= 200 (:status (put {})))))
    (testing "settings change is public"
      (is (= 200 (:status (put {:booking {:minNoticeHours 12 :windowDays 90 :defaultSessionMinutes 75}}))))
      (is (= 75 (get-in (call e :get "/api/site-info") [:body :booking :defaultSessionMinutes]))))
    (testing "a category in use cannot be removed"
      (let [r (put {:portfolioCategories [{:id "portraits" :name "Portraits"}]})]
        (is (= 409 (:status r)))))
    (testing "new delivery area gets an id; inactive areas are hidden publicly"
      (let [r (put {:deliveryAreas [{:id "kigali-kanombe" :name "Kigali – Kanombe" :feeRwf nil :active true}
                                    {:name "Musanze" :feeRwf 5000 :active false}]})]
        (is (= 200 (:status r)))
        (is (= 2 (count (get-in r [:body :deliveryAreas]))))
        (is (= 1 (count (get-in (call e :get "/api/site-info") [:body :deliveryAreas]))))))
    (testing "invalid opening hours"
      (is (= 400 (:status (put {:openingHours [{:days ["mon"] :open "10:00" :close "09:00"}]})))))
    (put {:booking {:minNoticeHours 24 :windowDays 180 :defaultSessionMinutes 60}})))

(deftest ^{:doc "Tests staff management."} staff-management
  (let [e @env
        list (call e :get "/api/admin/staff" {:session @owner})
        support-id (:id (first (filter #(= "support" (:role %)) (get-in list [:body :items]))))
        owner-id (:id (first (filter #(= "owner" (:role %)) (get-in list [:body :items]))))]
    (is (= 200 (:status list)))
    (is (= 409 (:status (call e :post "/api/admin/staff" {:session @owner :body {:name "Dup Dup" :email "support@example.test"}}))))
    (is (= 409 (:status (call e :patch (str "/api/admin/staff/" owner-id) {:session @owner :body {:active false}}))) "owner cannot be switched off")
    (is (= 409 (:status (call e :delete (str "/api/admin/staff/" owner-id) {:session @owner}))))
    (testing "switching off ends sessions at once; switching on again allows sign in"
      (let [tmp (h/support-session e @owner "temp@example.test")
            tmp-id (get-in tmp [:user :id])]
        (is (= 200 (:status (call e :get "/api/admin/dashboard" {:session tmp}))))
        (is (= 200 (:status (call e :patch (str "/api/admin/staff/" tmp-id) {:session @owner :body {:active false}}))))
        (is (= 401 (:status (call e :get "/api/admin/dashboard" {:session tmp}))))
        (is (= 401 (:status (call e :post "/api/admin/auth/login" {:body {:email "temp@example.test" :password "another long password 12"} :ip "5.5.5.5"}))))
        (is (= 200 (:status (call e :patch (str "/api/admin/staff/" tmp-id) {:session @owner :body {:active true}}))))
        (is (= 204 (:status (call e :delete (str "/api/admin/staff/" tmp-id) {:session @owner}))))))
    (testing "reset link is emailed (queued)"
      (is (= 202 (:status (call e :post (str "/api/admin/staff/" support-id "/reset-password-link") {:session @owner})))))))

(deftest ^{:doc "Tests merchandise order lifecycle."} merchandise-order-lifecycle
  (let [e @env
        img (get-in (call e :get "/api/portfolio") [:body :items 0 :image :id])
        p (call e :post "/api/admin/products"
                {:session @owner
                 :body {:name "Framed Print" :category "framed-art" :description "A3 print" :details "Wood frame" :priceRwf 30000
                        :optionGroups [] :images [{:imageId img :alt "Print"}] :isNew false :featured true :status "published"}})
        pid (get-in p [:body :id])
        o (call e :post "/api/merchandise-orders"
                {:idem (uuid) :ip "30.0.0.1"
                 :body {:customer {:name "Dan Test" :phone "+250788555666" :email "dan@example.test"}
                        :delivery {:areaText "Remera" :address "KG 11 Ave" :landmark nil :notes nil}
                        :items [{:productId pid :optionIds [] :quantity 1}]
                        :agreement {:slug "merchandise-order-terms" :version 1 :accepted true} :website ""}})
        id (get-in o [:body :id])
        act (fn [action & [body s]] (call e :post (str "/api/admin/merchandise-orders/" id "/" action) {:session (or s @owner) :body body}))]
    (is (= 201 (:status o)))
    (testing "fee must be given when it is still to be confirmed"
      (is (= 400 (:status (act "approve" {:deliveryFeeRwf nil :noteToCustomer nil})))))
    (testing "approve is idempotent for the same fee and conflicts for another"
      (let [a1 (act "approve" {:deliveryFeeRwf 2000 :noteToCustomer "Thanks"} @support)
            a2 (act "approve" {:deliveryFeeRwf 2000 :noteToCustomer "Thanks"})]
        (is (= 200 (:status a1)))
        (is (= 32000 (get-in a1 [:body :totalRwf])))
        (is (= (get-in a1 [:body :history]) (get-in a2 [:body :history])))
        (is (= 409 (:status (act "approve" {:deliveryFeeRwf 3000 :noteToCustomer nil}))))))
    (testing "payment then delivery"
      (is (= 409 (:status (act "reject" {:reason "Too late"}))))
      (is (= 200 (:status (act "mark-paid" {:method "cash" :amountRwf 32000}))))
      (is (= 200 (:status (act "mark-paid" {:method "cash" :amountRwf 32000}))))
      (is (= 409 (:status (act "mark-paid" {:method "cash" :amountRwf 1}))))
      (let [d (act "mark-delivered" {})]
        (is (= "delivered" (get-in d [:body :status])))
        (is (= 200 (:status (act "mark-delivered" {}))))))
    (testing "pay on delivery needs payment details; reject path"
      (let [mk #(call e :post "/api/merchandise-orders"
                      {:idem (uuid) :ip "30.0.0.2"
                       :body {:customer {:name "Eli Test" :phone "+250788555667" :email "eli@example.test"}
                              :delivery {:areaId "kigali-kanombe" :address "KG 12 Ave"}
                              :items [{:productId pid :optionIds [] :quantity 1}]
                              :agreement {:slug "merchandise-order-terms" :version 1 :accepted true}}})
            o2 (mk) id2 (get-in o2 [:body :id])
            call2 (fn [a b] (call e :post (str "/api/admin/merchandise-orders/" id2 "/" a) {:session @owner :body b}))]
        (call2 "approve" {:deliveryFeeRwf 1000})
        (is (= 400 (:status (call2 "mark-delivered" {}))))
        (is (= "delivered" (get-in (call2 "mark-delivered" {:method "cash" :amountRwf 31000}) [:body :status])))
        (let [o3 (mk) id3 (get-in o3 [:body :id])
              r (call e :post (str "/api/admin/merchandise-orders/" id3 "/reject") {:session @owner :body {:reason "Out of area"}})
              pub (call e :get (str "/api/merchandise-orders/" (get-in o3 [:body :reference]) "?token=" (get-in o3 [:body :accessToken])))]
          (is (= "rejected" (get-in r [:body :status])))
          (is (= "Out of area" (get-in pub [:body :customerNote]))))))))

(deftest ^{:doc "Tests documents and acceptance records."} documents-and-acceptance-records
  (let [e @env
        d (call e :get "/api/admin/documents/session-booking-agreement" {:session @owner})
        sub (submit! "40.0.0.1" {:date "2026-12-15" :startTime "10:00"})
        ref (get-in sub [:body :reference])]
    (is (= "draft" (get-in d [:body :reviewStatus])))
    (is (= 1 (count (get-in d [:body :versions]))))
    (testing "acceptance record is retrievable and stable"
      (let [a (call e :get (str "/api/admin/acceptances?subjectReference=" ref) {:session @owner})
            rec (first (get-in a [:body :items]))]
        (is (= 1 (count (get-in a [:body :items]))))
        (is (= "bookingRequest" (:subjectType rec)))
        (is (= "session-booking-agreement" (:documentSlug rec)))
        (is (= 1 (:version rec)))
        (is (= "Ann Test" (:customerName rec)))))
    (testing "new version keeps the old text and makes old acceptances stale for new requests"
      (let [v (call e :post "/api/admin/documents/session-booking-agreement/versions"
                    {:session @owner :body {:body "## New text" :summaryPoints ["Point"] :changeNote "Edited"}})]
        (is (= 201 (:status v)))
        (is (= 2 (get-in v [:body :version])))
        (is (= 1 (get-in (call e :get "/api/documents/session-booking-agreement?version=1") [:body :version])))
        (is (= 2 (get-in (call e :get "/api/documents/session-booking-agreement") [:body :version])))
        (is (= "agreementVersionStale" (get-in (call e :post "/api/booking-requests" {:body (h/booking-body {:preferredSlot {:date "2026-12-15" :startTime "11:00"}}) :idem (uuid) :ip "40.0.0.2"}) [:body :error :code])))
        (is (= 201 (:status (submit! "40.0.0.3" {:date "2026-12-15" :startTime "11:00"}))))
        (is (= 200 (:status (call e :post "/api/admin/documents/privacy/mark-reviewed" {:session @owner}))))))))

(deftest ^{:doc "Tests outbox keeps messages when email is unset then sends."} outbox-keeps-messages-when-email-is-unset-then-sends
  (let [e @env
        sub (submit! "41.0.0.1" {:date "2026-12-16" :startTime "10:00"})
        id (get-in sub [:body :id])
        msgs #(get-in (call e :get (str "/api/admin/booking-requests/" id) {:session @owner}) [:body :messages])]
    (testing "queued and not sent; the API never fails because of email"
      (is (= 201 (:status sub)))
      (is (= ["queued"] (distinct (map :status (msgs))))))
    (testing "delivery pass sends when a sender address exists, with retry on failure"
      (let [conn (:aurashots/db (:system e))
            deliver (fn [f] (notifications/deliver-due! conn f "from@example.test" @(:clock e)))]
        (let [r (deliver (fn [_] (throw (ex-info "smtp down" {}))))]
          (is (pos? (:failed r)))
          (is (= ["queued"] (distinct (map :status (msgs)))) "failed attempt stays queued with back-off")
          (is (zero? (:sent (deliver (fn [_] true)))) "not due yet"))
        (swap! (:clock e) #(.plusSeconds % 120))
        (let [sent (atom [])
              r (deliver (fn [m] (swap! sent conj m)))]
          (is (pos? (:sent r)))
          (is (= ["sent"] (distinct (map :status (msgs)))))
          (is (some #(re-find #"Ann Test" (:text %)) @sent))
          (is (some #(= "ann@example.test" (:to %)) @sent)))))
    (testing "resend requeues"
      (let [m (first (msgs))
            r (call e :post (str "/api/admin/booking-requests/" id "/messages/" (:id m) "/resend") {:session @owner})]
        (is (= 202 (:status r)))
        (is (= "queued" (get-in r [:body :status])))))))
