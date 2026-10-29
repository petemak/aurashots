(ns aurashots.features.pages-core-test
  "Tests for the pure SEO generation of the `pages` slice (QA report 2, minor items): Open Graph image size and alt
  for photo-based pages, and the floor on generated meta descriptions."
  (:require [aurashots.features.pages.core :as core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def base
  "Public base URL used in the tests."
  "https://x.example.test")

(def info
  "A minimal site-info map."
  {:address "Kanombe, Kigali" :phone nil :email nil :openingHours [] :social {}})

(def service
  "A service detail map with a photo."
  {:slug "single-session" :name "Single Session" :priceRwf 50000
   :shortDescription "One person, one outfit, a relaxed hour with our photographer in Kigali."
   :description "Longer text." :mainImage {:id "i1" :url "/media/i1/large.jpg" :width 1600 :height 1067 :alt "A portrait"}})

(defn region
  "Rendered SEO region for `seo`."
  [seo]
  (core/seo-region (core/with-default-image seo base)))

(deftest ^{:doc "Tests photo og tags."} photo-og-image-has-size-and-alt
  (testing "service with a photo"
    (let [r (region (core/service-seo base service info))]
      (is (str/includes? r "og:image\" content=\"https://x.example.test/media/i1/large.jpg\""))
      (is (str/includes? r "og:image:width\" content=\"1600\""))
      (is (str/includes? r "og:image:height\" content=\"1067\""))
      (is (str/includes? r "og:image:alt\" content=\"A portrait\""))
      (is (not (str/includes? r "1200")) "the default 1200x630 size is not used for photos")))
  (testing "a photo without alt text falls back to the item name"
    (let [r (region (core/service-seo base (assoc-in service [:mainImage :alt] "") info))]
      (is (str/includes? r "og:image:alt\" content=\"Single Session\""))))
  (testing "product with a photo"
    (let [r (region (core/product-seo base {:slug "tee" :name "Aura Tee" :description "Soft cotton tee with the Aura Shots mark on the chest." :priceRwf 15000
                                            :availability "inStock" :mainImage {:url "/media/p1/large.jpg" :width 1200 :height 1600 :alt "The tee"}}))]
      (is (str/includes? r "og:image:width\" content=\"1200\""))
      (is (str/includes? r "og:image:alt\" content=\"The tee\""))))
  (testing "without a photo the shared image keeps its own size and alt"
    (let [r (region (core/service-seo base (assoc service :mainImage {:url nil}) info))]
      (is (str/includes? r "/assets/og-image.png"))
      (is (str/includes? r "og:image:width\" content=\"1200\"")))))

(defn description-of
  "The meta description in a rendered region."
  [r]
  (second (re-find #"name=\"description\" content=\"([^\"]*)\"" r)))

(deftest ^{:doc "Tests the description floor."} descriptions-have-a-floor
  (testing "a long source text is used as it is"
    (is (= (:shortDescription service) (description-of (region (core/service-seo base service info))))))
  (testing "a short or missing source falls back to a composed description"
    (doseq [short ["" "a" "Short text."]]
      (let [d (description-of (region (core/service-seo base (assoc service :shortDescription short :description "x") info)))]
        (is (>= (count d) core/description-floor) d)
        (is (str/starts-with? d "Single Session – ") d)
        (is (str/ends-with? d "Aura Shots, Kigali.") d))))
  (testing "a usable short summary is kept inside the composed text"
    (is (str/includes? (description-of (region (core/service-seo base (assoc service :shortDescription "Studio hour, one outfit" :description "") info)))
                       "Studio hour, one outfit")))
  (testing "products"
    (let [d (description-of (region (core/product-seo base {:slug "t" :name "Tee" :description "" :priceRwf 1 :availability "inStock"
                                                            :mainImage {:url nil}})))]
      (is (>= (count d) core/description-floor) d)
      (is (str/starts-with? d "Tee – "))))
  (testing "legal pages: the 1-character key point of QA report 2"
    (let [d (description-of (region (core/legal-seo base "privacy" {:title "Privacy Policy" :summaryPoints ["a"]} nil)))]
      (is (>= (count d) core/description-floor) d)
      (is (str/starts-with? d "Privacy Policy of Aura Shots")))
    (is (= "This policy explains in plain words which personal details we keep and for how long."
           (description-of (region (core/legal-seo base "privacy" {:title "Privacy Policy" :summaryPoints ["This policy explains in plain words which personal details we keep and for how long."]} nil)))))))
