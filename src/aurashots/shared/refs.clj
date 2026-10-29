(ns aurashots.shared.refs
  "Public reference codes (`AS-S-XXXXXXXX`, `AS-M-XXXXXXXX`) and opaque ids (api-contract.md section 1).
  Shared pure code: randomness is supplied by the caller as a `java.util.Random`-compatible object so
  tests can seed it.")

(def ^:private alphabet
  "Uppercase letters and digits without I, O, 0 and 1."
  "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

(defn reference
  "Builds a reference code. `kind` is `:session` (prefix `AS-S-`) or `:merchandise` (prefix `AS-M-`);
  `rng` is a `java.util.Random`. Returns a string such as `AS-S-7K2F9QXA`."
  [kind ^java.util.Random rng]
  (str (case kind :session "AS-S-" :merchandise "AS-M-")
       (apply str (repeatedly 8 #(.charAt ^String alphabet (.nextInt rng (count alphabet)))))))

(defn new-id
  "Returns a new opaque identifier string (a random UUID). Side effect: consumes system randomness."
  []
  (str (random-uuid)))

(defn slugify
  "Turns a human name into a URL slug: lower case ASCII letters, digits and single dashes.
  Returns \"item\" when nothing usable remains."
  [s]
  (let [norm (-> (java.text.Normalizer/normalize (str s) java.text.Normalizer$Form/NFD)
                 (.replaceAll "\\p{M}" "")
                 .toLowerCase
                 (.replaceAll "[^a-z0-9]+" "-")
                 (.replaceAll "(^-+|-+$)" ""))]
    (if (empty? norm) "item" norm)))
