(ns aurashots.shared.whatsapp
  "Builds click-to-chat WhatsApp links (scope-checklist.md, Notifications: WhatsApp is manual). Shared pure
  code used by the booking, orders and enquiries slices."
  (:require [clojure.string :as str]))

(defn digits
  "Reduces `phone` to international digits without `+`. A local Rwandan number such as `0790114749` becomes
  `250790114749`. Returns nil when no digits remain."
  [phone]
  (let [d (str/replace (str phone) #"\D" "")]
    (cond (str/blank? d) nil
          (and (str/starts-with? d "0") (= 10 (count d))) (str "250" (subs d 1))
          :else d)))

(defn percent-encode
  "Percent-encodes `s` as UTF-8 for use in a URL query value (unreserved characters are kept)."
  [s]
  (apply str (map (fn [b]
                    (let [c (bit-and b 0xff)]
                      (if (or (<= 48 c 57) (<= 65 c 90) (<= 97 c 122) (#{45 46 95 126} c))
                        (char c)
                        (format "%%%02X" c))))
                  (.getBytes ^String s "UTF-8"))))

(defn link
  "Returns `https://wa.me/<digits>?text=<message>` for `phone` and prefilled `message`, or nil when the phone has no digits."
  [phone message]
  (when-let [d (digits phone)]
    (str "https://wa.me/" d "?text=" (percent-encode message))))
