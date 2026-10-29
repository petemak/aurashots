(ns aurashots.platform.server
  "Platform component `server` (architecture.md section 2.2): assembles the route table from every slice's route
  specs, compiles each into a Pedestal route with the shared interceptors, and starts the Pedestal (Jetty)
  connector with the Sawtooth router. This is the one platform namespace that requires slices' `routes`
  namespaces (together with `aurashots.system`). Startup fails when a route lacks access or input metadata, or
  when two routes share a method and path."
  (:require [aurashots.features.auth.routes :as auth]
            [aurashots.features.booking.routes :as booking]
            [aurashots.features.calendar.routes :as calendar]
            [aurashots.features.catalog.routes :as catalog]
            [aurashots.features.dashboard.routes :as dashboard]
            [aurashots.features.documents.routes :as documents]
            [aurashots.features.enquiries.routes :as enquiries]
            [aurashots.features.media.routes :as media]
            [aurashots.features.merch.routes :as merch]
            [aurashots.features.orders.routes :as orders]
            [aurashots.features.pages.routes :as pages]
            [aurashots.features.portfolio.routes :as portfolio]
            [aurashots.features.site.routes :as site]
            [aurashots.features.storefront.routes :as storefront]
            [aurashots.platform.http-core.interceptors :as ic]
            [integrant.core :as ig]
            [io.pedestal.connector :as connector]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition.table :as table]
            [taoensso.timbre :as log]))

(defn route-specs
  "Returns the vector of every route spec of every slice for the dependency map `deps`."
  [deps]
  (vec (mapcat #(% deps)
               [site/routes media/routes catalog/routes merch/routes storefront/routes portfolio/routes
                calendar/routes booking/routes orders/routes enquiries/routes documents/routes auth/routes
                dashboard/routes pages/routes])))

(defn- with-head-routes
  "Adds a HEAD twin for every GET spec (same handler; the base interceptor drops the body)."
  [specs]
  (into specs
        (for [s specs :when (= :get (:method s))]
          (assoc s :method :head :name (keyword (namespace (:name s)) (str (name (:name s)) "-head"))))))

(defn route-table
  "Compiles `specs` into the Pedestal routing table. Throws when specs are incomplete or ambiguous."
  [deps specs]
  (let [specs (with-head-routes specs)
        dup (->> specs (group-by (juxt :method :path)) (filter #(> (count (val %)) 1)) keys)]
    (when (seq dup) (throw (ex-info "Duplicate routes" {:routes dup})))
    (binding [route/*print-routing-table* false]
      (route/expand-routes (table/table-routes {} (mapv #(ic/compile-route deps %) specs))))))

(defn build-connector
  "Creates the unstarted Pedestal connector for `deps` (keys `:conn :clock :config :resolve-session :not-found-fn`
  plus the slice dependencies). The same connector serves real HTTP and the in-process test requests."
  [deps]
  (let [{:keys [host port]} (get-in deps [:config :http])
        table (route-table deps (route-specs deps))]
    (binding [route/*print-routing-table* false]
      (-> (connector/default-connector-map host port)
          (connector/with-interceptor (ic/base-interceptor deps))
          (connector/with-interceptor route/query-params)
          (connector/with-interceptor (route/router table :sawtooth))
          (connector/with-interceptor route/path-params-decoder)
          (jetty/create-connector nil)))))

(defmethod ig/init-key :aurashots/server [_ {:keys [deps start?]}]
  (let [c (build-connector deps)]
    (when (not= false start?)
      (connector/start! c)
      (log/info "HTTP server started" {:port (get-in deps [:config :http :port])}))
    {:connector c}))

(defmethod ig/halt-key! :aurashots/server [_ {:keys [connector]}]
  (connector/stop! connector))
