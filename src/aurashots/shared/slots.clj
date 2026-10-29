(ns aurashots.shared.slots
  "Slot grid and availability rules as pure functions of data (architecture.md section 2.3, shared code;
  api-contract.md section 2.7). The same functions answer the public availability endpoint, booking
  submission and staff confirmation, so the rules cannot drift apart.

  Terms: a *block* is `{:date \"YYYY-MM-DD\" :startTime \"HH:mm\"|nil :endTime \"HH:mm\"|nil}` (nil times
  mean the whole day); a *session* is a confirmed booking
  `{:id .. :date .. :startTime .. :durationMinutes n}`. Pending requests never appear here."
  (:require [aurashots.shared.time :as t])
  (:import (java.time Instant LocalDate)))

(def default-session-minutes
  "Session length used when neither the services nor the settings give one (owner answer Q7)."
  60)

(defn- hours-for
  "Opening hours `{:open m :close m}` (minutes) for `date` from the settings `openingHours`, or nil if closed."
  [opening-hours ^LocalDate date]
  (let [k (name (t/day-key date))
        entry (first (filter #(some #{k} (map name (:days %))) opening-hours))]
    (when entry
      (let [o (t/parse-time (:open entry)) c (t/parse-time (:close entry))]
        (when (and o c (< o c)) {:open o :close c})))))

(defn- overlaps?
  "True when the half-open intervals [a1,a2) and [b1,b2) intersect."
  [a1 a2 b1 b2]
  (and (< a1 b2) (< b1 a2)))

(defn- block-interval
  "Minute interval `[from to)` of a block (whole day when times are nil)."
  [{:keys [startTime endTime]}]
  (if (and startTime endTime)
    [(t/parse-time startTime) (t/parse-time endTime)]
    [0 1440]))

(defn conflict
  "Checks a candidate session `[start, start+duration)` on `date-str` against `blocks` and `sessions`.
  `ignore-id` (optional) excludes one session, used when rescheduling it.
  Returns :blocked, :alreadyConfirmed or nil. Blocks are reported before sessions."
  ([date-str start duration blocks sessions]
   (conflict date-str start duration blocks sessions nil))
  ([date-str start duration blocks sessions ignore-id]
   (let [end (+ start duration)]
     (cond
       (some (fn [b] (and (= date-str (:date b))
                          (let [[b1 b2] (block-interval b)] (overlaps? start end b1 b2))))
             blocks)
       :blocked

       (some (fn [s] (and (= date-str (:date s))
                          (not= ignore-id (:id s))
                          (let [s1 (t/parse-time (:startTime s))]
                            (overlaps? start end s1 (+ s1 (:durationMinutes s))))))
             sessions)
       :alreadyConfirmed))))

(defn- day-slots
  "All grid starts of a day with an `:available` flag and the reason when not (:tooSoon, :blocked, :taken).
  `earliest` is the first bookable instant (now + notice)."
  [{:keys [settings session-minutes blocks sessions ^Instant earliest]} ^LocalDate date]
  (when-let [{:keys [open close]} (hours-for (:openingHours settings) date)]
    (let [slot-min (or (get-in settings [:booking :slotMinutes]) 30)
          date-str (str date)
          last-start (- close session-minutes)]
      (vec (for [s (range open (inc last-start) slot-min)
                 :let [c (conflict date-str s session-minutes blocks sessions)
                       soon? (.isBefore (t/local->instant date s) earliest)
                       reason (cond soon? :tooSoon c (if (= c :blocked) :blocked :taken))]]
             {:startTime (t/minutes->time s) :available (nil? reason) :reason reason})))))

(defn- day-status
  "Day status keyword from the day's slots and context (see api-contract.md section 2.7)."
  [{:keys [settings blocks ^Instant now]} ^LocalDate date slots]
  (let [today (t/today now)
        window (get-in settings [:booking :windowDays] 180)
        date-str (str date)]
    (cond
      (.isBefore date today) :past
      (.isAfter date (.plusDays today window)) :closed
      (nil? slots) :closed
      (some #(and (= date-str (:date %)) (nil? (:startTime %))) blocks) :blocked
      (some :available slots) :available
      (and (seq slots) (every? #(= :tooSoon (:reason %)) slots)) :tooSoon
      (and (seq slots) (every? #(#{:tooSoon :blocked} (:reason %)) slots)
           (some #(= :blocked (:reason %)) slots)) :blocked
      :else :full)))

(defn availability
  "Computes availability for the inclusive date range.
  Keys of the argument map: `:now` (Instant), `:settings` (site settings map with `:openingHours` and
  `:booking {:minNoticeHours :windowDays :slotMinutes}`), `:from` and `:to` (LocalDate),
  `:session-minutes` (positive int), `:blocks`, `:sessions`, `:with-slots?` (true for every day, or a set of date
  strings whose slot lists are included).
  Returns `{:timeZone \"Africa/Kigali\" :days [{:date :status :slots?}]}` with contract field names."
  [{:keys [now settings from to with-slots?] :as ctx}]
  (let [notice (get-in settings [:booking :minNoticeHours] 24)
        ctx (assoc ctx :earliest (.plusSeconds ^Instant now (* 3600 notice)))]
    {:timeZone "Africa/Kigali"
     :days (vec (for [d (t/date-range from to)
                      :let [slots (day-slots ctx d)
                            status (day-status ctx d slots)
                            out (cond-> {:date (str d) :status (name status)}
                                  (or (true? with-slots?) (contains? (or with-slots? #{}) (str d)))
                                  (assoc :slots (if (#{:available :full :tooSoon :blocked} status)
                                                  (mapv #(select-keys % [:startTime :available]) slots)
                                                  [])))]]
                  out))}))

(defn slot-available?
  "True when `start-time` (HH:mm) on `date` (LocalDate) is a free, bookable grid slot for a session of
  `session-minutes`, under the same rules as `availability`. Takes the same context keys (`:now`,
  `:settings`, `:blocks`, `:sessions`, `:session-minutes`)."
  [ctx ^LocalDate date start-time]
  (let [notice (get-in (:settings ctx) [:booking :minNoticeHours] 24)
        ctx (assoc ctx :earliest (.plusSeconds ^Instant (:now ctx) (* 3600 notice)))
        window (get-in (:settings ctx) [:booking :windowDays] 180)
        today (t/today (:now ctx))]
    (boolean
     (and (not (.isBefore date today))
          (not (.isAfter date (.plusDays today window)))
          (some #(and (= start-time (:startTime %)) (:available %)) (day-slots ctx date))))))

(defn session-length
  "Session length in minutes: the sum of `service-minutes` (a collection of ints or nils, each nil
  falling back to `default-minutes`), or `default-minutes` when the collection is empty. A nil
  default falls back to `default-session-minutes`."
  [service-minutes default-minutes]
  (let [dflt (or default-minutes default-session-minutes)]
    (if (empty? service-minutes)
      dflt
      (reduce + (map #(or % dflt) service-minutes)))))
