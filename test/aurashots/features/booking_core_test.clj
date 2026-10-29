(ns aurashots.features.booking-core-test
  "Tests for the pure booking state machine (api-contract.md section 4)."
  (:require [aurashots.features.booking.core :as core]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant)))

(def now "Fixed test time." (Instant/parse "2026-10-29T08:00:00Z"))

(def pending
  "A pending two-service request."
  (core/build-request {:customer {:name "Ann" :phone "+250788111222" :email "a@x.test"} :contactPreference "email"
                       :preferredSlot {:date "2026-11-20" :startTime "10:30"} :notes nil}
                      [{:id "s1" :name "Single Session" :priceRwf 50000} {:id "s2" :name "Special" :priceRwf 10000}]
                      {:id "b1" :reference "AS-S-AAAAAAAA" :token-hash "h" :now now :duration-minutes 120}))

(defn code-of
  "Error code thrown by calling `f`, or nil."
  [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))

(deftest ^{:doc "Tests snapshot and total."} snapshot-and-total
  (is (= 60000 (:totalRwf pending)))
  (is (= :pending (:status pending)))
  (is (= 1 (count (:history pending)))))

(deftest ^{:doc "Tests confirm flow."} confirm-flow
  (let [slot {:date "2026-11-20" :startTime "10:30"}
        r (core/confirm pending slot "See you" "Staff" now nil)]
    (is (= :confirmed (:status (:booking r))))
    (is (= :bookingConfirmed (:email r)) "same as requested slot")
    (is (= :bookingTimeChanged (:email (core/confirm pending {:date "2026-11-21" :startTime "10:30"} nil "Staff" now nil))))
    (testing "repeat with same slot is a no-op, different slot is a conflict"
      (is (false? (:changed? (core/confirm (:booking r) slot nil "Staff" now nil))))
      (is (= :conflict (code-of #(core/confirm (:booking r) {:date "2026-11-21" :startTime "10:30"} nil "Staff" now nil)))))))

(deftest ^{:doc "Tests illegal transitions."} illegal-transitions
  (is (= :conflict (code-of #(core/complete pending "S" now))))
  (is (= :conflict (code-of #(core/reschedule pending {:date "2026-11-21" :startTime "10:30"} nil "S" now nil))))
  (is (= :conflict (code-of #(core/mark-paid pending {:method "cash" :amountRwf 1} "S" now))))
  (let [declined (:booking (core/decline pending "Fully booked" "S" now))]
    (is (= :declined (:status declined)))
    (is (false? (:changed? (core/decline declined "Fully booked" "S" now))))
    (is (= :conflict (code-of #(core/decline declined "Other reason" "S" now))))
    (is (= :conflict (code-of #(core/confirm declined {:date "2026-11-20" :startTime "10:30"} nil "S" now nil))))))

(deftest ^{:doc "Tests payment rules."} payment-rules
  (let [c (:booking (core/confirm pending {:date "2026-11-20" :startTime "10:30"} nil "S" now nil))
        paid (:booking (core/mark-paid c {:method "cash" :amountRwf 60000} "S" now))]
    (is (= "paid" (get-in paid [:payment :status])))
    (is (false? (:changed? (core/mark-paid paid {:method "cash" :amountRwf 60000} "S" now))))
    (is (= :conflict (code-of #(core/mark-paid paid {:method "mobileMoney" :amountRwf 60000} "S" now))))
    (is (= :completed (:status (:booking (core/complete paid "S" now)))))))

(deftest ^{:doc "Tests public view hides internal data."} public-view-hides-internal-data
  (let [v (core/public-view pending {:slug "x" :version 1 :acceptedAt now} "tok")]
    (is (not (contains? v :internalNotes)))
    (is (not (contains? v :tokenHash)))
    (is (= "tok" (:accessToken v)))))
