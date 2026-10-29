(ns aurashots.features.history-test
  "Fitness check F44: booking and order `history` and `internalNotes` are append-only. Every state transition of the
  pure booking and order state machines is run, including repeated (no-op) calls, and the previous vectors must be a
  prefix of the new ones; a transition that changes something adds history entries and a repeated call adds none."
  (:require [aurashots.features.booking.core :as booking]
            [aurashots.features.orders.core :as orders]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant)))

(def now "Fixed test time." (Instant/parse "2026-10-29T08:00:00Z"))

(defn prefix?
  "True when vector `a` is a prefix of vector `b`."
  [a b]
  (and (<= (count a) (count b)) (= (vec a) (vec (take (count a) b)))))

(defn run-steps
  "Applies each step `[label f]` (f: record -> {:booking|:order r :changed? bool}) in turn from `init`, checking after
  every step that history and notes only grew. `k` is `:booking` or `:order`. Returns the final record."
  [k init steps]
  (reduce (fn [rec [label f]]
            (let [r (f rec)
                  rec' (k r)]
              (testing label
                (is (prefix? (:history rec) (:history rec')) "history keeps its earlier entries")
                (is (prefix? (:internalNotes rec) (:internalNotes rec')) "notes keep their earlier entries")
                (when (contains? r :changed?)
                  (let [added (- (count (:history rec')) (count (:history rec)))]
                    (is (if (:changed? r) (pos? added) (zero? added)) "a change adds entries, a repeat adds none"))))
              rec'))
          init steps))

(def slot "A confirmed slot." {:date "2026-11-20" :startTime "10:30"})

(def pending-booking
  "A pending request."
  (booking/build-request {:customer {:name "Ann" :phone "+250788111222" :email "a@x.test"} :contactPreference "email"
                          :preferredSlot slot :notes nil}
                         [{:id "s1" :name "Single Session" :priceRwf 50000}]
                         {:id "b1" :reference "AS-S-AAAAAAAA" :token-hash "h" :now now :duration-minutes 60}))

(deftest ^{:doc "Tests booking history is append-only."} booking-history-is-append-only
  (let [note (fn [b] (booking/add-note b "call back" "Staff" "n1" now))
        pay {:method "cash" :amountRwf 50000}]
    (testing "confirm, reschedule, pay, complete with repeats and notes in between"
      (let [end (run-steps :booking pending-booking
                           [["note" note]
                            ["confirm" #(booking/confirm % slot "See you" "Staff" now nil)]
                            ["confirm again" #(booking/confirm % slot "See you" "Staff" now nil)]
                            ["reschedule" #(booking/reschedule % {:date "2026-11-21" :startTime "11:00"} nil "Staff" now :blocked)]
                            ["reschedule again" #(booking/reschedule % {:date "2026-11-21" :startTime "11:00"} nil "Staff" now nil)]
                            ["note 2" note]
                            ["mark paid" #(booking/mark-paid % pay "Staff" now)]
                            ["mark paid again" #(booking/mark-paid % pay "Staff" now)]
                            ["complete" #(booking/complete % "Staff" now)]
                            ["complete again" #(booking/complete % "Staff" now)]])]
        (is (= ["submitted" "note added" "confirmed" "rescheduled" "note added" "paid" "completed"]
               (map :action (:history end))))
        (is (= 2 (count (:internalNotes end))))))
    (testing "decline and a repeated decline"
      (let [end (run-steps :booking pending-booking
                           [["decline" #(booking/decline % "Fully booked" "Staff" now)]
                            ["decline again" #(booking/decline % "Fully booked" "Staff" now)]])]
        (is (= ["submitted" "declined"] (map :action (:history end))))))))

(def pending-order
  "A pending order with a known delivery fee."
  (orders/build-order {:customer {:name "Dan" :phone "+250788555666" :email "d@x.test"}
                       :delivery {:areaText "Remera" :address "KG 11 Ave"}}
                      [{:productId "p1" :name "Print" :optionLabels [] :unitPriceRwf 30000 :quantity 1 :lineTotalRwf 30000}]
                      {:feeRwf 2000}
                      {:id "o1" :reference "AS-M-AAAAAAAA" :token-hash "h" :now now}))

(deftest ^{:doc "Tests order history is append-only."} order-history-is-append-only
  (let [note (fn [o] (orders/add-note o "packed" "Staff" "n1" now))
        pay {:method "cash" :amountRwf 32000}]
    (testing "approve, pay, deliver with repeats and notes"
      (let [end (run-steps :order pending-order
                           [["note" note]
                            ["approve" #(orders/approve % {:deliveryFeeRwf 2000 :noteToCustomer "Thanks"} "Staff" now)]
                            ["approve again" #(orders/approve % {:deliveryFeeRwf 2000 :noteToCustomer "Thanks"} "Staff" now)]
                            ["mark paid" #(orders/mark-paid % pay "Staff" now)]
                            ["mark paid again" #(orders/mark-paid % pay "Staff" now)]
                            ["note 2" note]
                            ["deliver" #(orders/mark-delivered % {} "Staff" now)]
                            ["deliver again" #(orders/mark-delivered % {} "Staff" now)]])]
        (is (= ["submitted" "note added" "approved" "paid" "note added" "delivered"] (map :action (:history end))))))
    (testing "pay on delivery"
      (run-steps :order pending-order
                 [["approve" #(orders/approve % {:deliveryFeeRwf 2000 :noteToCustomer nil} "Staff" now)]
                  ["deliver with payment" #(orders/mark-delivered % pay "Staff" now)]]))
    (testing "reject and a repeated reject"
      (run-steps :order pending-order
                 [["reject" #(orders/reject % "Out of area" "Staff" now)]
                  ["reject again" #(orders/reject % "Out of area" "Staff" now)]]))))
