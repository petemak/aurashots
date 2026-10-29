(ns aurashots.features.merch-core-test
  "Tests for the pure merchandise rules: availability and order-line resolution."
  (:require [aurashots.features.merch.core :as core]
            [aurashots.features.orders.core :as orders]
            [clojure.test :refer [deftest is]])
  (:import (java.time Instant)))

(def product
  "A product with two option groups."
  {:id "p1" :name "Aura Tee" :status :published :priceRwf 15000 :inStock true :createdAt (Instant/now)
   :optionGroups [{:name "Size" :options [{:id "m" :label "M" :inStock true} {:id "l" :label "L" :inStock false}]}
                  {:name "Colour" :options [{:id "k" :label "Black" :inStock true}]}]})

(deftest ^{:doc "Tests availability rules."} availability-rules
  (is (= "inStock" (core/availability product)))
  (is (= "soldOut" (core/availability (assoc product :inStock false))))
  (is (= "soldOut" (core/availability (assoc-in product [:optionGroups 1 :options 0 :inStock] false)))))

(deftest ^{:doc "Tests resolve lines."} resolve-lines
  (let [ok (core/resolve-line product {:productId "p1" :optionIds ["m" "k"] :quantity 2})]
    (is (:ok ok))
    (is (= 30000 (get-in ok [:line :lineTotalRwf])))
    (is (= ["M" "Black"] (get-in ok [:line :optionLabels]))))
  (is (= :soldOut (:reason (core/resolve-line product {:productId "p1" :optionIds ["l" "k"] :quantity 1}))))
  (is (= :badOption (:reason (core/resolve-line product {:productId "p1" :optionIds ["m"] :quantity 1}))) "one option per group")
  (is (= :badOption (:reason (core/resolve-line product {:productId "p1" :optionIds ["zzz" "k"] :quantity 1}))))
  (is (= :unpublished (:reason (core/resolve-line (assoc product :status :hidden) {:productId "p1" :optionIds ["m" "k"] :quantity 1}))))
  (is (= :removed (:reason (core/resolve-line nil {:productId "x" :optionIds [] :quantity 1})))))

(deftest ^{:doc "Tests quote fee note."} quote-fee-note
  (let [results [(assoc (core/resolve-line product {:productId "p1" :optionIds ["m" "k"] :quantity 1}) :productId "p1")]
        lines [{:productId "p1" :optionIds ["m" "k"] :quantity 1}]]
    (is (= {:subtotalRwf 15000 :deliveryFeeRwf nil :totalRwf nil :feeNote "toBeConfirmed"}
           (select-keys (orders/quote-body results lines nil) [:subtotalRwf :deliveryFeeRwf :totalRwf :feeNote])))
    (is (= {:totalRwf 17000 :feeNote nil}
           (select-keys (orders/quote-body results lines {:feeRwf 2000}) [:totalRwf :feeNote])))))
