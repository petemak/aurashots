(ns aurashots.shared.time
  "Pure date and time helpers shared by all slices (architecture.md section 2.3, shared code).
  Business rules run in the Africa/Kigali zone; storage uses UTC instants. Nothing here reads
  the clock: callers pass `now` in."
  (:import (java.time DayOfWeek Instant LocalDate LocalDateTime LocalTime ZoneId ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)))

(def zone
  "The business time zone (api-contract.md section 1)."
  (ZoneId/of "Africa/Kigali"))

(def ^:private offset-formatter DateTimeFormatter/ISO_OFFSET_DATE_TIME)

(defn instant->str
  "Renders an `Instant` as ISO 8601 with the business-zone offset, e.g. `2026-11-14T10:30:00+02:00`.
  Returns nil for nil."
  [^Instant i]
  (when i
    (.format offset-formatter (.truncatedTo (ZonedDateTime/ofInstant i zone)
                                            java.time.temporal.ChronoUnit/SECONDS))))

(defn millis->instant
  "Converts epoch milliseconds to an `Instant` (nil stays nil)."
  [ms]
  (when ms (Instant/ofEpochMilli ms)))

(defn parse-date
  "Parses `YYYY-MM-DD` into a `LocalDate`. Returns nil when `s` is not a real calendar date."
  [s]
  (when (string? s)
    (try (LocalDate/parse s) (catch DateTimeParseException _ nil))))

(defn parse-time
  "Parses `HH:mm` into minutes after midnight. Returns nil when invalid."
  [s]
  (when (and (string? s) (re-matches #"([01]\d|2[0-3]):[0-5]\d" s))
    (let [t (LocalTime/parse s)] (+ (* 60 (.getHour t)) (.getMinute t)))))

(defn minutes->time
  "Formats minutes after midnight as `HH:mm`."
  [m]
  (format "%02d:%02d" (quot m 60) (rem m 60)))

(defn today
  "The business-zone `LocalDate` for the instant `now`."
  [^Instant now]
  (.toLocalDate (ZonedDateTime/ofInstant now zone)))

(defn local->instant
  "Converts a local date string and minutes-after-midnight in the business zone to an `Instant`."
  [^LocalDate date minutes]
  (.toInstant (ZonedDateTime/of (LocalDateTime/of date (LocalTime/of (quot minutes 60) (rem minutes 60))) zone)))

(defn day-key
  "Returns the weekday keyword (`:mon` .. `:sun`) of a `LocalDate`."
  [^LocalDate date]
  (let [^DayOfWeek d (.getDayOfWeek date)]
    ([:mon :tue :wed :thu :fri :sat :sun] (dec (.getValue d)))))

(defn date-range
  "Returns the vector of `LocalDate`s from `from` to `to` inclusive (empty if `to` < `from`)."
  [^LocalDate from ^LocalDate to]
  (loop [d from acc []]
    (if (.isAfter d to) acc (recur (.plusDays d 1) (conj acc d)))))

(defn epoch-ms
  "Epoch milliseconds of an `Instant`."
  [^Instant i]
  (.toEpochMilli i))

(defn utc-now-str
  "ISO-8601 UTC string of `Instant` (used in logs)."
  [^Instant i]
  (str (.atOffset i ZoneOffset/UTC)))
