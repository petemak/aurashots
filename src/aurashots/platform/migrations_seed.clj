(ns aurashots.platform.migrations-seed
  "Seed data written by migration 0002 (architecture.md section 3.3): default settings, the six placeholder policy
  documents (drafts, to be reviewed by a human before launch), the five service packages with the prices of
  business-context.md, a neutral placeholder image, and the six sample photos from `business/images/shots` in the
  portfolio. Every step takes `{:conn :config :now}` and upserts by unique key, so re-running is harmless.
  Contains no secrets. Real photos for the four services other than Single Session are still to be supplied."
  (:require [aurashots.platform.crypto :as crypto]
            [aurashots.platform.images :as images]
            [aurashots.platform.media-store :as media-store]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [taoensso.timbre :as log])
  (:import (java.awt Color)
           (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (javax.imageio ImageIO)))

(defn seed-settings
  "Writes the default settings (contact details, opening hours, booking rules, one delivery area with its fee still
  to be confirmed, two portfolio categories). TikTok, Facebook and the owner email stay nil (`<TBD>`)."
  [{:keys [conn]}]
  (d/transact! conn
               [{:setting/id "main"
                 :setting/data
                 {:phone "+250 790 114 749" :whatsapp "+250790114749" :email nil
                  :address "Kanombe, Kigali, Rwanda"
                  :social {:instagram "https://www.instagram.com/aura_shxts/" :tiktok nil :facebook nil}
                  :openingHours [{:days ["mon" "tue" "wed" "thu" "fri" "sat"] :open "08:00" :close "22:00"}
                                 {:days ["sun"] :open "10:00" :close "21:30"}]
                  :booking {:minNoticeHours 24 :windowDays 180 :defaultSessionMinutes 60}
                  :deliveryAreas [{:id "kigali-kanombe" :name "Kigali – Kanombe" :feeRwf nil :active true}]
                  :portfolioCategories [{:id "portraits" :name "Portraits"} {:id "lifestyle" :name "Lifestyle"}]}}]))

(def ^:private documents
  "The six documents: slug, title, whether acceptance is required, summary points and Markdown body."
  [{:slug "terms-of-sale" :title "Terms of Sale" :requires false
    :summary ["Sessions and merchandise are requested online and confirmed by Aura Shots before they take effect."
              "Prices are in Rwandan francs (RwF) and are paid in cash or mobile money after confirmation."]
    :body "## Terms of Sale\n\nThese terms apply to every session request and merchandise order made through this website.\n\n## How it works\n\n- You send a request. It is **not** a confirmed booking or order until Aura Shots confirms or approves it.\n- Prices are shown in Rwandan francs (RwF). Payment is made in cash or mobile money after we have agreed the details with you.\n- Sessions are by appointment only.\n\n## Changes\n\nPrices and packages can change. A change never affects a request you have already sent.\n\n## Contact\n\nQuestions about these terms: use the contact page."}
   {:slug "refund-policy" :title "Refund Policy" :requires false
    :summary ["All bookings are a commitment and we strictly enforce a no-refund policy."
              "Contact us as early as possible if you need to change the date of a confirmed session."]
    :body "## Refund Policy\n\nBecause we invest time and effort in preparing and setting up every session, **all bookings are a commitment and we do not give refunds**.\n\n## Changing a date\n\nIf you need to move a confirmed session, tell us as early as you can. We will do our best to find another time, but this is not guaranteed.\n\n## Merchandise\n\nThe conditions for merchandise are in the order terms."}
   {:slug "privacy" :title "Privacy Policy" :requires false
    :summary ["We store the contact details you give us so that we can answer your request."
              "We also keep a record of the agreement you accepted, with the date and version."]
    :body "## Privacy Policy\n\nThis policy explains what Aura Shots stores about you.\n\n## What we store\n\n- Your name, phone number, email address and, where you give it, your address.\n- The details of your session request, merchandise order or message.\n- A record of the agreement you accepted: the version, the date and time, and technical details such as your IP address.\n\n## Why\n\nWe use these details only to reply to you, to arrange sessions and deliveries, and to show what you agreed to.\n\n## Your rights\n\nYou can ask us what we hold about you and ask us to correct or delete it, unless we must keep it for our records. Use the contact page."}
   {:slug "order-terms" :title "Order Terms" :requires false
    :summary ["Merchandise orders are requests that we review and approve before they are final."
              "Approved orders are paid in cash or mobile money and delivered to the address you give."]
    :body "## Order Terms\n\nThese terms cover merchandise: clothing, prints and framed art.\n\n- An order is a request until Aura Shots approves it.\n- After approval we confirm the delivery fee with you. Payment is made in cash or mobile money.\n- Please check your delivery address and phone number carefully.\n- Items shown as sold out cannot be ordered."}
   {:slug "session-booking-agreement" :title "Session Booking Agreement" :requires true
    :summary ["Your request is not confirmed until Aura Shots confirms the date and time with you."
              "Bookings are a commitment and we strictly enforce a no-refund policy."
              "We store your details and this acceptance so that we can arrange your session."]
    :body "## Session Booking Agreement\n\nBy sending this request you agree to the following.\n\n- Your request is **not a confirmed booking** until Aura Shots confirms the date and time with you.\n- A confirmed booking is a commitment. We strictly enforce a **no-refund policy** (see the Refund Policy).\n- The final date, time, location and details are agreed with you after you send this request.\n- You pay in cash or mobile money after we have confirmed your session.\n- We store the details you give us and a record of this acceptance (version, date and time) as described in the Privacy Policy."}
   {:slug "merchandise-order-terms" :title "Merchandise Order Terms" :requires true
    :summary ["Your order is not final until Aura Shots approves it."
              "The delivery fee may be confirmed after you order."
              "We store your details and this acceptance so that we can deliver your order."]
    :body "## Merchandise Order Terms\n\nBy placing this order you agree to the following.\n\n- Your order is **a request** until Aura Shots approves it.\n- The delivery fee may be confirmed after you order. You will know the total before you pay.\n- You pay in cash or mobile money once the order is approved.\n- Please give a correct delivery address and phone number so that we can reach you.\n- We store the details you give us and a record of this acceptance (version, date and time) as described in the Privacy Policy."}])

(defn seed-documents
  "Writes the six documents as version 1 drafts (`reviewStatus :draft`) published by \"System\"."
  [{:keys [conn now]}]
  (doseq [{:keys [slug title requires summary body]} documents]
    (d/transact! conn
                 [{:docver/key (str slug "@1") :docver/slug slug :docver/version 1
                   :docver/data {:slug slug :version 1 :body body :summaryPoints summary :publishedAt now
                                 :publishedByName "System" :changeNote "Placeholder text for human review"
                                 :bodyHash (crypto/sha256-hex body)}}
                  {:document/slug slug
                   :document/data {:slug slug :title title :requiresAcceptance requires :reviewStatus :draft :currentVersion 1}}])))

(def ^:private samples
  "Sample photos: file, image id, portfolio caption, alt text, category and featured flag."
  [{:file "sampl1.jpg" :id "seed-photo-1" :caption "Lilac in the lift" :category "portraits" :featured true
    :alt "Woman in a lilac mini dress posing in a wood-panelled lift with one foot raised"}
   {:file "sampl2.jpg" :id "seed-photo-2" :caption "White fringe" :category "lifestyle" :featured false
    :alt "Woman in a white fringed mini dress standing in a warmly lit restaurant"}
   {:file "sampl3.jpg" :id "seed-photo-3" :caption "Evening in red" :category "lifestyle" :featured true
    :alt "Woman in a long red strapless dress posing by restaurant windows at night"}
   {:file "sampl4.jpg" :id "seed-photo-4" :caption "On the stairs" :category "lifestyle" :featured false
    :alt "Woman in a brown cut-out jumpsuit and gold heels seated on a staircase"}
   {:file "sampl5.jpg" :id "seed-photo-5" :caption "Orange against grey" :category "portraits" :featured true
    :alt "Woman with a short curly cut in an orange dress against grey wooden panels"}
   {:file "sampl6.jpg" :id "seed-photo-6" :caption "Balcony light" :category "portraits" :featured false
    :alt "Woman in a black leather top and brown trousers on a balcony above the city"}])

(defn- neutral-placeholder-bytes
  "A neutral dark-grey 800x1000 PNG, used when an item has no photo yet."
  ^bytes []
  (let [img (BufferedImage. 800 1000 BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)
        bos (ByteArrayOutputStream.)]
    (.setColor g (Color. 58 58 58))
    (.fillRect g 0 0 800 1000)
    (.dispose g)
    (ImageIO/write img "png" bos)
    (.toByteArray bos)))

(defn- import-image!
  "Processes `bytes`, writes the derivative files for image `id` and stores the image entity."
  [{:keys [conn config now]} id bytes]
  (let [store {:dir (io/file (:upload-dir config))}
        {:keys [width height files]} (images/process bytes)]
    (doseq [[size b] files] (media-store/write! store id size b))
    (d/transact! conn [{:image/id id :image/data {:id id :width width :height height :createdAt now}}])))

(defn seed-images
  "Creates the neutral placeholder image and imports the six sample photos from `:seed-images-dir`, then lists the
  photos in the portfolio. When the directory is missing a warning is logged and only the placeholder is created."
  [{:keys [conn config now] :as ctx}]
  (import-image! ctx "placeholder-neutral" (neutral-placeholder-bytes))
  (let [dir (io/file (:seed-images-dir config))]
    (if-not (.isDirectory dir)
      (log/warn "Seed photo directory not found; portfolio left empty" {:dir (str dir)})
      (doseq [[i {:keys [file id caption alt category featured]}] (map-indexed vector samples)]
        (import-image! ctx id (let [f (io/file dir file)] (with-open [in (io/input-stream f)] (.readAllBytes in))))
        (d/transact! conn [{:portfolio/id (str "seed-portfolio-" (inc i)) :portfolio/sortOrder i
                            :portfolio/image [:image/id id] :portfolio/categoryId category :portfolio/featured featured
                            :portfolio/data {:id (str "seed-portfolio-" (inc i)) :sortOrder i :createdAt now :imageId id
                                             :caption caption :description alt :categoryId category :featured featured}}])))))

(def ^:private conditions
  "Conditions text shared by the seed services (no invented numbers: details are agreed after the request)."
  "Appointment only. A booking is a commitment and we strictly enforce a no-refund policy. The date, time, location and final details are agreed with you after you send your request.")

(def ^:private services
  "The five seed packages with the prices of business-context.md. Contents are unknown, so they start empty
  for the owner to fill in."
  [{:slug "single-session" :name "Single Session" :price 50000 :featured true :photo "seed-photo-1"
    :short "A private, one-person editorial photo session in our signature style."
    :description "A one-person session in the Aura Shots style: stylised portraits with considered colour, dramatic lighting and main-character energy. We plan the look, location and outfits with you before the day."}
   {:slug "couple-session" :name "Couple Session" :price 100000 :featured true
    :short "An editorial session for two: partners, friends or family pairs."
    :description "A session for two people, shot with the same cinematic, editorial care as our solo work. We plan the story, styling and locations together with you."}
   {:slug "besties-package" :name "Besties Package" :price 12000 :featured false
    :short "A fun, colourful session for friends."
    :description "A session for friends who want stylish photos together. Details are agreed with you after you send your request."}
   {:slug "special-package" :name "Special Package" :price 10000 :featured false
    :short "A short session for a special occasion."
    :description "A short session for a special moment such as a birthday. Details are agreed with you after you send your request."}
   {:slug "weekend-sessions" :name "Weekend Sessions" :price 15000 :featured false
    :short "A relaxed session on a weekend date."
    :description "A session on a weekend date. Details are agreed with you after you send your request."}])

(defn seed-services
  "Writes the five services (published, with prices; Single Session uses sample photo 1, the others show the neutral
  placeholder until real photos are uploaded)."
  [{:keys [conn]}]
  (doseq [[i {:keys [slug name price featured short description photo]}] (map-indexed vector services)
          :let [id (str "seed-service-" slug)
                images (if (and photo (d/q '[:find ?e . :in $ ?i :where [?e :image/id ?i]] (d/db conn) photo))
                         [{:imageId photo :alt (str name ": sample portrait")}] [])]]
    (d/transact! conn
                 [(cond-> {:service/id id :service/slug slug :service/status :published :service/sortOrder i
                           :service/data {:id id :slug slug :name name :shortDescription short :description description
                                          :priceRwf price :sessionMinutes nil
                                          :contents {:editedPictures nil :videos nil :outfits nil :deliveryTime nil :locations nil}
                                          :conditions conditions :relatedProductIds [] :images images
                                          :featured featured :status :published :sortOrder i}}
                    (seq images) (assoc :service/imageRefs [[:image/id photo]]))])))
