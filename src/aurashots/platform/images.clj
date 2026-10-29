(ns aurashots.platform.images
  "Image decoding, validation and re-encoding used by the `media` slice and the seed migration
  (architecture.md section 5, File storage). Accepts JPEG, PNG and WebP by content (magic bytes), enforces
  the contract limits, honours the EXIF orientation, and always re-encodes to JPEG, which drops every
  metadata block including GPS location. Decoding runs one at a time (semaphore) to protect the heap."
  (:require [aurashots.shared.errors :as err])
  (:import (java.awt Color RenderingHints)
           (java.awt.geom AffineTransform)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.util.concurrent Semaphore)
           (javax.imageio IIOImage ImageIO ImageWriteParam)
           (javax.imageio.stream MemoryCacheImageOutputStream)))

(def max-bytes
  "Largest accepted upload, in bytes (api-contract.md section 3.7)."
  (* 10 1024 1024))

(def max-pixels
  "Largest accepted width or height, in pixels."
  6000)

(def sizes
  "Derivative sizes: name to long-edge pixels (api-contract.md section 1, Media)."
  {:thumb 400 :card 800 :large 1600})

(def ^:private decode-gate (Semaphore. 1))

(defn- magic
  "Detects the image type from the first bytes: :jpeg, :png, :webp or nil."
  [^bytes b]
  (when (>= (alength b) 12)
    (let [u #(bit-and 0xff (aget b %))]
      (cond
        (and (= 0xff (u 0)) (= 0xd8 (u 1)) (= 0xff (u 2))) :jpeg
        (and (= 0x89 (u 0)) (= 0x50 (u 1)) (= 0x4e (u 2)) (= 0x47 (u 3))) :png
        (and (= \R (char (u 0))) (= \I (char (u 1))) (= \F (char (u 2))) (= \F (char (u 3)))
             (= \W (char (u 8))) (= \E (char (u 9))) (= \B (char (u 10))) (= \P (char (u 11)))) :webp))))

(defn exif-orientation
  "Reads the EXIF orientation (1-8) from JPEG bytes; returns 1 when absent or unreadable."
  [^bytes b]
  (try
    (let [n (alength b)
          u8 #(bit-and 0xff (aget b %))
          u16be #(+ (* 256 (u8 %)) (u8 (inc %)))]
      (loop [pos 2]
        (if (>= (+ pos 4) n)
          1
          (let [marker (u16be pos) len (u16be (+ pos 2))]
            (cond
              (not= 0xff (u8 pos)) 1
              (= marker 0xffe1)
              (let [t (+ pos 10)               ; start of the TIFF header after "Exif\0\0"
                    le? (= 0x49 (u8 t))
                    r16 (fn [o] (if le? (+ (u8 o) (* 256 (u8 (inc o)))) (u16be o)))
                    r32 (fn [o] (if le?
                                  (+ (r16 o) (* 65536 (r16 (+ o 2))))
                                  (+ (* 65536 (r16 o)) (r16 (+ o 2)))))
                    ifd (+ t (r32 (+ t 4)))
                    cnt (r16 ifd)]
                (or (first (for [i (range cnt)
                                 :let [e (+ ifd 2 (* 12 i))]
                                 :when (= 0x0112 (r16 e))
                                 :let [v (r16 (+ e 8))]
                                 :when (<= 1 v 8)]
                             v))
                    1))
              (or (< marker 0xffe0) (> marker 0xffef)) 1
              :else (recur (+ pos 2 len)))))))
    (catch Exception _ 1)))

(defn- orient
  "Returns `img` rotated/flipped upright according to the EXIF `orientation` (1-8)."
  ^BufferedImage [^BufferedImage img orientation]
  (if (= 1 orientation)
    img
    (let [w (.getWidth img) h (.getHeight img)
          swap? (>= orientation 5)
          tx (case (int orientation)
               2 (AffineTransform. -1.0 0.0 0.0 1.0 w 0.0)
               3 (AffineTransform. -1.0 0.0 0.0 -1.0 w h)
               4 (AffineTransform. 1.0 0.0 0.0 -1.0 0.0 h)
               5 (AffineTransform. 0.0 1.0 1.0 0.0 0.0 0.0)
               6 (AffineTransform. 0.0 1.0 -1.0 0.0 h 0.0)
               7 (AffineTransform. 0.0 -1.0 -1.0 0.0 h w)
               8 (AffineTransform. 0.0 -1.0 1.0 0.0 0.0 w))
          out (BufferedImage. (if swap? h w) (if swap? w h) BufferedImage/TYPE_INT_RGB)
          g (.createGraphics out)]
      (.setColor g Color/WHITE)
      (.fillRect g 0 0 (.getWidth out) (.getHeight out))
      (.drawImage g img tx nil)
      (.dispose g)
      out)))

(defn- draw-scaled
  "Draws `img` scaled to `w` x `h` onto a white RGB canvas with bicubic interpolation."
  ^BufferedImage [^BufferedImage img w h]
  (let [out (BufferedImage. w h BufferedImage/TYPE_INT_RGB)
        g (.createGraphics out)]
    (.setRenderingHint g RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BICUBIC)
    (.setRenderingHint g RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
    (.setColor g Color/WHITE)
    (.fillRect g 0 0 w h)
    (.drawImage g img 0 0 w h nil)
    (.dispose g)
    out))

(defn- scale-long-edge
  "Scales so the longer edge equals `target` pixels (never enlarging), halving step by step for quality."
  ^BufferedImage [^BufferedImage img target]
  (let [w (.getWidth img) h (.getHeight img) long-edge (max w h)]
    (if (<= long-edge target)
      (draw-scaled img w h)
      (loop [cur img]
        (let [cw (.getWidth cur) ch (.getHeight cur) f (/ (double target) (max cw ch))]
          (if (< f 0.5)
            (recur (draw-scaled cur (max 1 (quot cw 2)) (max 1 (quot ch 2))))
            (draw-scaled cur (max 1 (Math/round (* cw f))) (max 1 (Math/round (* ch f))))))))))

(defn- encode-jpeg
  "Encodes an RGB image as JPEG bytes (quality 0.85, no metadata)."
  ^bytes [^BufferedImage img]
  (let [writer (.next (ImageIO/getImageWritersByFormatName "jpeg"))
        param (.getDefaultWriteParam writer)
        bos (ByteArrayOutputStream.)]
    (.setCompressionMode param ImageWriteParam/MODE_EXPLICIT)
    (.setCompressionQuality param (float 0.85))
    (with-open [ios (MemoryCacheImageOutputStream. bos)]
      (.setOutput writer ios)
      (.write writer nil (IIOImage. img nil nil) param)
      (.dispose writer))
    (.toByteArray bos)))

(defn- read-dimensions
  "Returns `[w h]` from the image header without decoding pixels, or nil when no reader accepts the bytes."
  [^bytes b]
  (with-open [iis (ImageIO/createImageInputStream (ByteArrayInputStream. b))]
    (let [readers (ImageIO/getImageReaders iis)]
      (when (.hasNext readers)
        (let [r (.next readers)]
          (try (.setInput r iis true true) [(.getWidth r 0) (.getHeight r 0)]
               (finally (.dispose r))))))))

(defn process
  "Validates and converts an uploaded image. `bytes` is the raw upload.
  Returns `{:width w :height h :files {:thumb bytes :card bytes :large bytes}}` where width and height are
  those of the `large` derivative. Throws a `validationFailed` error (see `aurashots.shared.errors`) when the
  file is too big, not JPEG/PNG/WebP, undecodable, or larger than 6000 px on either edge."
  [^bytes bytes]
  (when (> (alength bytes) max-bytes)
    (err/fail! :validationFailed "The image is larger than 10 MB." {:file "Maximum size is 10 MB."}))
  (when-not (magic bytes)
    (err/fail! :validationFailed "Only JPEG, PNG or WebP images are accepted." {:file "Unsupported file type."}))
  (let [[w h] (try (read-dimensions bytes) (catch Exception _ nil))]
    (when-not w
      (err/fail! :validationFailed "That file could not be read as an image." {:file "Unreadable image."}))
    (when (or (> w max-pixels) (> h max-pixels))
      (err/fail! :validationFailed "The image is larger than 6000 pixels on one side." {:file "Maximum is 6000 px."}))
    (.acquire decode-gate)
    (try
      (let [img (or (try (ImageIO/read (ByteArrayInputStream. bytes)) (catch Exception _ nil))
                    (err/fail! :validationFailed "That file could not be read as an image." {:file "Unreadable image."}))
            upright (orient img (if (= :jpeg (magic bytes)) (exif-orientation bytes) 1))
            files (into {} (for [[k px] sizes] [k (encode-jpeg (scale-long-edge upright px))]))
            large (ImageIO/read (ByteArrayInputStream. ^bytes (:large files)))]
        {:width (.getWidth large) :height (.getHeight large) :files files})
      (finally (.release decode-gate)))))
