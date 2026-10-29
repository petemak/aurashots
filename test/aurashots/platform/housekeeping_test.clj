(ns aurashots.platform.housekeeping-test
  "Tests of the weekly logical export in the `housekeeping` component (architecture.md section 8, Backup): the
  schedule in Africa/Kigali time, one export per week, atomic writing and retention of the newest files."
  (:require [aurashots.platform.housekeeping :as hk]
            [aurashots.test-helpers :as h]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant LocalDate)))

(def schedule "Sunday 03:00 Kigali time, keep 3." {:day :sun :hour 3 :keep 3})

(deftest ^{:doc "Tests the last scheduled export date."} last-export-due-uses-kigali-time
  ;; 2026-11-01 is a Sunday. Kigali is UTC+2.
  (is (= (LocalDate/of 2026 11 1) (hk/last-export-due (Instant/parse "2026-11-01T01:30:00Z") schedule)) "03:30 local, after the hour")
  (is (= (LocalDate/of 2026 10 25) (hk/last-export-due (Instant/parse "2026-11-01T00:30:00Z") schedule)) "02:30 local, before the hour")
  (is (= (LocalDate/of 2026 11 1) (hk/last-export-due (Instant/parse "2026-11-04T12:00:00Z") schedule)) "midweek")
  (is (= (LocalDate/of 2026 11 1) (hk/last-export-due (Instant/parse "2026-11-01T22:30:00Z") schedule)) "Sunday 22:30 UTC is Monday 00:30 local"))

(deftest ^{:doc "Tests weekly export writing and retention."} weekly-export-writes-once-and-prunes
  (let [env (h/start-system)
        conn (get-in env [:system :aurashots/db])
        dir (h/temp-dir)
        at #(Instant/parse %)
        names #(sort (map (fn [f] (.getName ^java.io.File f)) (.listFiles (io/file dir))))]
    (try
      (testing "first run writes the export of the Kigali date"
        (let [f (hk/weekly-export-if-due! conn dir (at "2026-11-01T02:00:00Z") schedule)]
          (is (some? f))
          (is (= ["export-20261101.edn"] (names)))
          (is (pos? (.length ^java.io.File f)))))
      (testing "a second run in the same week writes nothing"
        (is (nil? (hk/weekly-export-if-due! conn dir (at "2026-11-04T02:00:00Z") schedule)))
        (is (= ["export-20261101.edn"] (names))))
      (testing "not due before the hour on the next Sunday"
        (is (nil? (hk/weekly-export-if-due! conn dir (at "2026-11-08T00:30:00Z") schedule))))
      (testing "weekly runs keep only the newest three and leave no temporary file"
        (doseq [t ["2026-11-08T02:00:00Z" "2026-11-15T02:00:00Z" "2026-11-22T02:00:00Z"]]
          (is (some? (hk/weekly-export-if-due! conn dir (at t) schedule))))
        (is (= ["export-20261108.edn" "export-20261115.edn" "export-20261122.edn"] (names))))
      (finally (h/stop-system env)))))
