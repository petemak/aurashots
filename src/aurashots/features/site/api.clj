(ns aurashots.features.site.api
  "Slice `site`, public API for other slices (architecture.md section 2.4): read access to settings,
  delivery areas, portfolio categories and the contact address. Other slices must call only this namespace."
  (:require [aurashots.features.site.core :as core]
            [aurashots.features.site.store :as store]))

(defn settings
  "Returns the settings map (`:phone :whatsapp :email :address :social :openingHours :booking
  :deliveryAreas :portfolioCategories`)."
  [conn]
  (store/get-settings conn))

(defn public-info
  "Returns the public `site-info` body. `owner-email` is the configured fallback address (may be nil)."
  [conn owner-email]
  (core/public-info (store/get-settings conn) owner-email))

(defn contact-email
  "Returns the address used as sender and owner alert recipient: the settings email, else `owner-email`
  from configuration, else nil."
  [conn owner-email]
  (or (:email (store/get-settings conn)) owner-email))

(defn delivery-area
  "Returns the delivery area `{:id :name :feeRwf :active}` with `area-id`, or nil."
  [conn area-id]
  (first (filter #(= area-id (:id %)) (:deliveryAreas (store/get-settings conn)))))

(defn category
  "Returns the portfolio category `{:id :name}` with `category-id`, or nil."
  [conn category-id]
  (first (filter #(= category-id (:id %)) (:portfolioCategories (store/get-settings conn)))))

(defn save-settings!
  "Replaces the settings with `settings` (used by the admin settings route). Side effect: database write."
  [conn settings]
  (store/save-settings! conn settings))
