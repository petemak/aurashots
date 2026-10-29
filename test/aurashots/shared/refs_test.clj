(ns aurashots.shared.refs-test
  "Tests for reference codes and slugs."
  (:require [aurashots.shared.refs :as refs]
            [aurashots.shared.whatsapp]
            [clojure.test :refer [deftest is]]))

(deftest ^{:doc "Tests reference format."} reference-format
  (let [r (refs/reference :session (java.util.Random. 1))
        m (refs/reference :merchandise (java.util.Random. 2))]
    (is (re-matches #"AS-S-[A-HJ-NP-Z2-9]{8}" r))
    (is (re-matches #"AS-M-[A-HJ-NP-Z2-9]{8}" m))))

(deftest ^{:doc "Tests slugs."} slugs
  (is (= "couple-session" (refs/slugify "Couple Session")))
  (is (= "cafe-au-lait" (refs/slugify "Café  au lait!")))
  (is (= "item" (refs/slugify "!!!"))))

(deftest ^{:doc "Tests the WhatsApp link builder."} whatsapp-links
  (is (= "https://wa.me/250790114749?text=Hello%20Ann%2C%20%C3%A9" (aurashots.shared.whatsapp/link "0790 114 749" "Hello Ann, é")))
  (is (= "250788111222" (aurashots.shared.whatsapp/digits "+250 788 111 222")))
  (is (nil? (aurashots.shared.whatsapp/link "n/a" "x"))))
