(ns aurashots.features.media.routes
  "Slice `media`, HTTP routes (api-contract.md sections 1 Media and 3.7): serves image derivatives and handles
  admin upload and delete. Decoding and re-encoding is delegated to `aurashots.platform.images`."
  (:require [aurashots.features.media.core :as core]
            [aurashots.features.media.store :as store]
            [aurashots.platform.http-core.interceptors :as ic]
            [aurashots.platform.http-core.response :as resp]
            [aurashots.platform.http-core.response-schemas :as rs]
            [aurashots.platform.http-core.schemas :as s]
            [aurashots.platform.images :as images]
            [aurashots.platform.media-store :as media-store]
            [aurashots.shared.errors :as err]
            [aurashots.shared.refs :as refs]
            [clojure.string :as str]))

(defn- serve-image
  "Serves `<imageId>/<size>.jpg` from the media store with year-long immutable caching."
  [{:keys [media-store]} {{:keys [image-id file]} :path-params}]
  (let [size (some-> file (str/replace #"\.jpg$" "") keyword)
        f (media-store/file-for media-store image-id size)]
    (if (and f (.isFile f) (str/ends-with? (str file) ".jpg"))
      {:status 200
       :headers {"Content-Type" "image/jpeg" "Cache-Control" "public, max-age=31536000, immutable"}
       :body f}
      (err/fail! :notFound "That image does not exist."))))

(defn- upload!
  "Handles `POST /api/admin/images`: parses the multipart body, validates and re-encodes the file, writes the
  derivatives and the metadata. Returns 201 with the `Image` (alt empty until the image is attached)."
  [{:keys [conn media-store clock]} req]
  (let [ct (get-in req [:headers "content-type"])
        _ (when-not (and ct (str/starts-with? (str/lower-case ct) "multipart/form-data"))
            (err/fail! :validationFailed "Send the image as multipart/form-data." {:file "required"}))
        body (ic/read-body (:body req) (+ images/max-bytes 65536))
        parts (core/parse-multipart ct body)
        file (get parts "file")
        _ (when-not (and file (pos? (alength ^bytes (:bytes file))))
            (err/fail! :validationFailed "Choose an image file to upload." {:file "required"}))
        {:keys [width height files]} (images/process (:bytes file))
        id (refs/new-id)
        image {:id id :width width :height height
              :createdAt ((:now-fn clock))}]
    (try
      (doseq [[size bytes] files] (media-store/write! media-store id size bytes))
      (store/insert-image! conn image)
      (catch Exception e (media-store/delete! media-store id) (throw e)))
    (resp/created (core/image-view image ""))))

(defn routes
  "Returns the route specs of the media slice. `deps` keys: `:conn`, `:media-store`, `:clock`."
  [{:keys [conn media-store] :as deps}]
  [{:name :media/serve :method :get :path "/media/:image-id/:file" :public true :no-input true
    :response {200 rs/Raw}
    :handler (fn [req] (serve-image deps req))}
   {:name :media/upload :method :post :path "/api/admin/images" :roles #{:owner :support}
    :no-input true :multipart true :response {201 rs/Image}
    :handler (fn [req] (upload! deps req))}
   {:name :media/delete :method :delete :path "/api/admin/images/:id" :roles #{:owner :support}
    :schema {:path (s/closed-map [:id s/Id])} :response {204 rs/NoBody}
    :handler (fn [{{{:keys [id]} :path} :params}]
               (when-not (store/get-image conn id) (err/fail! :notFound "That image does not exist."))
               (when (store/in-use? conn id)
                 (err/fail! :conflict "That image is still used by a service, product or portfolio photo."))
               (store/delete-image! conn id)
               (media-store/delete! media-store id)
               (resp/no-content))}])
