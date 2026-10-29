(ns aurashots.shared.slots-test
  "Tests for the pure slot and availability rules (api-contract.md section 2.7)."
  (:require [aurashots.shared.slots :as slots]
            [aurashots.shared.time :as t]
            [clojure.test :refer [deftest is]])
  (:import (java.time Instant)))

(def settings
  "Default opening hours and booking rules."
  {:openingHours [{:days ["mon" "tue" "wed" "thu" "fri" "sat"] :open "08:00" :close "22:00"}
                  {:days ["sun"] :open "10:00" :close "21:30"}]
   :booking {:minNoticeHours 24 :windowDays 180 :slotMinutes 30}})

(def now "A Thursday, 10:00 local." (Instant/parse "2026-10-29T08:00:00Z")) ; 10:00 local, Thursday

(defn ctx
  "Default slot context merged with `m`."
  [& [m]]
  (merge {:now now :settings settings :blocks [] :sessions [] :session-minutes 60} m))

(defn day
  "First day of the availability computed for `from`..`to`."
  [from to & [m]]
  (first (:days (slots/availability (assoc (ctx m) :from (t/parse-date from) :to (t/parse-date to) :with-slots? true)))))

(deftest ^{:doc "Tests grid and last start."} grid-and-last-start
  (let [d (day "2026-11-02" "2026-11-02")] ; Monday
    (is (= "available" (:status d)))
    (is (= "08:00" (:startTime (first (:slots d)))))
    (is (= "21:00" (:startTime (last (:slots d)))) "last start = close minus one hour")))

(deftest ^{:doc "Tests sunday closes at 2130."} sunday-closes-at-2130
  (let [d (day "2026-11-01" "2026-11-01")] ; Sunday
    (is (= "10:00" (:startTime (first (:slots d)))))
    (is (= "20:30" (:startTime (last (:slots d)))))
    (is (= "20:00" (:startTime (last (:slots (day "2026-11-01" "2026-11-01" {:session-minutes 90}))))))))

(deftest ^{:doc "Tests session length changes last start."} session-length-changes-last-start
  (is (= "20:00" (:startTime (last (:slots (day "2026-11-02" "2026-11-02" {:session-minutes 120})))))))

(deftest ^{:doc "Tests notice window and past."} notice-window-and-past
  (is (= "past" (:status (day "2026-10-28" "2026-10-28"))))
  (is (= "tooSoon" (:status (day "2026-10-29" "2026-10-29"))))
  (let [d (day "2026-10-30" "2026-10-30")] ; earliest bookable 10:00 local
    (is (= "available" (:status d)))
    (is (false? (:available (first (filter #(= "09:30" (:startTime %)) (:slots d))))))
    (is (true? (:available (first (filter #(= "10:00" (:startTime %)) (:slots d)))))))
  (is (= "closed" (:status (day "2027-06-01" "2027-06-01"))) "beyond the booking window"))

(deftest ^{:doc "Tests blocks and confirmed sessions."} blocks-and-confirmed-sessions
  (is (= "blocked" (:status (day "2026-11-02" "2026-11-02" {:blocks [{:date "2026-11-02" :startTime nil :endTime nil}]}))))
  (let [d (day "2026-11-02" "2026-11-02" {:blocks [{:date "2026-11-02" :startTime "10:00" :endTime "12:00"}]})
        av (fn [t] (:available (first (filter #(= t (:startTime %)) (:slots d)))))]
    (is (= "available" (:status d)))
    (is (false? (av "09:30")) "a 60 minute session starting 09:30 overlaps the block")
    (is (true? (av "09:00")))
    (is (false? (av "11:30")))
    (is (true? (av "12:00"))))
  (let [d (day "2026-11-02" "2026-11-02" {:sessions [{:id "a" :date "2026-11-02" :startTime "14:00" :durationMinutes 90}]})
        av (fn [t] (:available (first (filter #(= t (:startTime %)) (:slots d)))))]
    (is (false? (av "13:30")))
    (is (false? (av "15:00")))
    (is (true? (av "15:30")))))

(deftest ^{:doc "Tests full when every slot taken."} full-when-every-slot-taken
  (is (= "full" (:status (day "2026-11-02" "2026-11-02"
                              {:sessions [{:id "a" :date "2026-11-02" :startTime "08:00" :durationMinutes 840}]})))))

(deftest ^{:doc "Tests conflict and ignore."} conflict-and-ignore
  (let [sessions [{:id "a" :date "2026-11-02" :startTime "10:00" :durationMinutes 60}]]
    (is (= :alreadyConfirmed (slots/conflict "2026-11-02" 630 60 [] sessions)))
    (is (nil? (slots/conflict "2026-11-02" 630 60 [] sessions "a")) "ignoring the session itself")
    (is (= :blocked (slots/conflict "2026-11-02" 630 60 [{:date "2026-11-02" :startTime nil :endTime nil}] sessions)))))

(deftest ^{:doc "Tests session length rules."} session-length-rules
  (is (= 60 (slots/session-length [] nil)))
  (is (= 45 (slots/session-length [] 45)))
  (is (= 150 (slots/session-length [90 nil] 60)) "nil falls back to the default; lengths are summed"))

(deftest ^{:doc "Tests slot available single."} slot-available-single
  (is (true? (slots/slot-available? (ctx) (t/parse-date "2026-11-02") "10:30")))
  (is (false? (slots/slot-available? (ctx) (t/parse-date "2026-11-02") "10:15")) "off the 30 minute grid")
  (is (false? (slots/slot-available? (ctx) (t/parse-date "2026-11-02") "21:30")) "last start exceeded")
  (is (false? (slots/slot-available? (ctx) (t/parse-date "2026-10-28") "10:00"))))
