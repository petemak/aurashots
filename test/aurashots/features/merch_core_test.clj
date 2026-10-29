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

(deftest ^{:doc "QA D4 sequence A: mark sold out, re-enable options via PATCH availability with optionId."} reenable-option-clears-sold-out
  (let [sold (core/set-availability product nil false)
        _ (is (= "soldOut" (core/availability sold)))
        one (core/set-availability sold "m" true)
        both (core/set-availability one "k" true)]
    (is (= "soldOut" (core/availability sold)))
    (is (= "inStock" (core/availability both)) "all groups have an option in stock again")
    (is (true? (:inStock both)))
    (is (= "soldOut" (core/availability (core/set-availability both "m" false))) "disabling the only size still sells out")
    (is (false? (:inStock (core/set-availability (core/set-availability product nil false) "k" false))) "disabling never raises the flag")))

(deftest ^{:doc "QA D4 sequence B: mark sold out, tick options in the edit form and save."} edit-form-save-clears-sold-out
  (let [sold (core/set-availability product nil false)
        body {:name "Aura Tee" :priceRwf 15000 :status :published
              :optionGroups [{:name "Size" :options [{:id "m" :label "M" :inStock true} {:id "l" :label "L" :inStock true}]}
                             {:name "Colour" :options [{:id "k" :label "Black" :inStock true}]}]}
        saved (core/build-product body {:existing sold :now (Instant/now)})]
    (is (true? (:inStock saved)))
    (is (= "inStock" (core/availability saved))))
  (let [sold (core/set-availability product nil false)
        body {:name "Aura Tee" :priceRwf 15000 :status :published
              :optionGroups (:optionGroups sold)}
        saved (core/build-product body {:existing sold :now (Instant/now)})]
    (is (= "soldOut" (core/availability saved)) "an unchanged sold-out form stays sold out")))
