(ns aurashots.platform.http-core.response
  "Small constructors for handler response maps (architecture.md section 2.2, `http-core`). Handlers
  return plain maps; the base interceptor encodes JSON bodies and adds security headers.")

(def public-cache
  "`Cache-Control` for public catalogue reads (api-contract.md section 1, Caching)."
  "public, max-age=60, stale-while-revalidate=300")

(defn ok
  "200 response with a JSON `body`, never cached."
  [body]
  {:status 200 :headers {"Cache-Control" "no-store"} :body body})

(defn created
  "201 response with a JSON `body`."
  [body]
  {:status 201 :headers {"Cache-Control" "no-store"} :body body})

(defn accepted
  "202 response with a JSON `body`."
  [body]
  {:status 202 :headers {"Cache-Control" "no-store"} :body body})

(defn no-content
  "204 response without a body."
  []
  {:status 204 :headers {"Cache-Control" "no-store"}})

(defn cached
  "200 response with a JSON `body`, public catalogue caching and an ETag computed by the base interceptor."
  [body]
  {:status 200 :headers {"Cache-Control" public-cache} :body body :etag? true})

(defn paginated
  "Builds the standard paginated body `{items page pageSize total}` from the full sequence `all`, 1-based `page`
  and `page-size`, applying `f` (default identity) only to the returned slice."
  ([all page page-size] (paginated all page page-size identity))
  ([all page page-size f]
   (let [page (or page 1) page-size (or page-size 24)]
     {:items (mapv f (->> all (drop (* (dec page) page-size)) (take page-size)))
      :page page :pageSize page-size :total (count all)})))
