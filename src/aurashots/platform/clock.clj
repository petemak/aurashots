(ns aurashots.platform.clock
  "Platform component `clock` (architecture.md section 2.2): the only source of \"now\". Injectable so
  tests control time. The component value is a map `{:now-fn f}` where `f` returns a `java.time.Instant`."
  (:require [integrant.core :as ig])
  (:import (java.time Instant)))

(defn now
  "Returns the current `Instant` according to `clock` (a map with `:now-fn`)."
  ^Instant [clock]
  ((:now-fn clock)))

(defmethod ig/init-key :aurashots/clock [_ {:keys [now-fn]}]
  {:now-fn (or now-fn #(Instant/now))})
