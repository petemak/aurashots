(ns aurashots.features.calendar.core
  "Slice `calendar`, pure logic (architecture.md section 2.3): expands block requests into per-day blocks and finds
  confirmed sessions affected by new blocks. No I/O."
  (:require [aurashots.shared.slots :as slots]
            [aurashots.shared.time :as t]))

(def max-span-days
  "Longest date range one block request may cover."
  366)

(defn validate-request
  "Returns a `fields` map of problems with the block request `{:fromDate :toDate :startTime :endTime}` (empty
  when valid)."
  [{:keys [fromDate toDate startTime endTime]}]
  (let [from (t/parse-date fromDate)
        to (t/parse-date (or toDate fromDate))]
    (cond-> {}
      (and from to (.isBefore to from)) (assoc "toDate" "must not be before fromDate")
      (and from to (> (count (t/date-range from to)) max-span-days)) (assoc "toDate" "range is too long")
      (not= (nil? startTime) (nil? endTime)) (assoc "startTime" "give both start and end time, or neither")
      (and startTime endTime (>= (t/parse-time startTime) (t/parse-time endTime))) (assoc "endTime" "must be later than the start time"))))

(defn expand
  "Expands a block request into one block map per day: `{:id :date :startTime :endTime :reason :createdByName}`.
  `new-id` is a no-argument function returning fresh ids."
  [{:keys [fromDate toDate startTime endTime reason]} created-by new-id]
  (let [from (t/parse-date fromDate) to (t/parse-date (or toDate fromDate))]
    (mapv (fn [d] {:id (new-id) :date (str d) :startTime startTime :endTime endTime
                   :reason reason :createdByName created-by})
          (t/date-range from to))))

(defn affected-sessions
  "Returns those of `sessions` (`{:date :startTime :endTime ..}`) that overlap any of the new `blocks`."
  [sessions blocks]
  (filterv (fn [s]
             (let [start (t/parse-time (:startTime s))
                   duration (- (t/parse-time (:endTime s)) start)]
               (slots/conflict (:date s) start duration blocks [])))
           sessions))
