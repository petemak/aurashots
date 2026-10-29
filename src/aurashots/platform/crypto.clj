(ns aurashots.platform.crypto
  "Small cryptographic helpers shared by slices (architecture.md section 5): random tokens, SHA-256, keyed
  hashing of tokens with the `TOKEN_PEPPER` secret, and constant-time comparison. Tokens are stored only as
  keyed hashes."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64 HexFormat)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private rng (SecureRandom.))

(defn random-token
  "Returns `n-bytes` (default 16, i.e. 128 bits) of secure randomness as an unpadded URL-safe string."
  ([] (random-token 16))
  ([n-bytes]
   (let [b (byte-array n-bytes)]
     (.nextBytes rng b)
     (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))))

(defn secure-random
  "The shared `SecureRandom` instance (a `java.util.Random`), for reference generation."
  ^java.util.Random []
  rng)

(defn sha256-hex
  "Lower-case hex SHA-256 of the UTF-8 bytes of string `s`."
  [^String s]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))))

(defn hmac-hex
  "Lower-case hex HMAC-SHA256 of `s` keyed with `pepper` (a string)."
  [^String pepper ^String s]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes pepper StandardCharsets/UTF_8) "HmacSHA256"))
    (.formatHex (HexFormat/of) (.doFinal mac (.getBytes s StandardCharsets/UTF_8)))))

(defn constant-time=
  "Compares two strings in constant time; nil never equals anything."
  [a b]
  (boolean (and a b (MessageDigest/isEqual (.getBytes ^String a StandardCharsets/UTF_8)
                                           (.getBytes ^String b StandardCharsets/UTF_8)))))
