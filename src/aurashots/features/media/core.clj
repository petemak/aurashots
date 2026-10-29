(ns aurashots.features.media.core
  "Slice `media`, pure logic (architecture.md section 2.3): builds the contract `Image` shape and parses
  `multipart/form-data` bodies for image upload. No I/O."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)))

(defn image-view
  "Builds the contract `Image` map `{id url thumbUrl width height alt}` from the stored image data
  `{:id :width :height}` and the `alt` text chosen where the image is used (may be \"\")."
  [{:keys [id width height]} alt]
  {:id id
   :url (str "/media/" id "/large.jpg")
   :thumbUrl (str "/media/" id "/thumb.jpg")
   :width width
   :height height
   :alt (or alt "")})

(defn- header-value
  "Extracts `key=\"value\"` (or unquoted) from a header line, or nil."
  [line key]
  (second (re-find (re-pattern (str "(?i)" key "=\"?([^\";]*)\"?")) line)))

(defn parse-multipart
  "Parses a `multipart/form-data` body. `content-type` is the request header, `body` a byte array.
  Returns a map of field name (string) to `{:filename s|nil :content-type s|nil :bytes byte-array}`, or nil when
  the content type has no boundary. Bytes are mapped one-to-one through ISO-8859-1 so binary data survives."
  [content-type ^bytes body]
  (when-let [boundary (some-> content-type (header-value "boundary"))]
    (let [text (String. body StandardCharsets/ISO_8859_1)
          delim (str "--" boundary)
          parts (rest (str/split text (re-pattern (java.util.regex.Pattern/quote delim))))]
      (into {}
            (for [part parts
                  :when (not (str/starts-with? part "--"))
                  :let [idx (str/index-of part "\r\n\r\n")]
                  :when idx
                  :let [head (subs part 0 idx)
                        content (subs part (+ idx 4))
                        content (if (str/ends-with? content "\r\n") (subs content 0 (- (count content) 2)) content)
                        disp (first (filter #(str/starts-with? (str/lower-case %) "content-disposition") (str/split-lines head)))
                        ctype (first (filter #(str/starts-with? (str/lower-case %) "content-type") (str/split-lines head)))
                        field (some-> disp (header-value "name"))]
                  :when field]
              [field {:filename (some-> disp (header-value "filename"))
                      :content-type (some-> ctype (str/split #":" 2) second str/trim)
                      :bytes (.getBytes content StandardCharsets/ISO_8859_1)}])))))
