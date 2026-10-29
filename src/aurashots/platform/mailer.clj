(ns aurashots.platform.mailer
  "Platform component `mailer` (architecture.md section 2.2): sends exactly one email over SMTP (Gmail) when
  asked. No queueing or retry logic lives here; the outbox worker owns that. The component value is
  `{:configured? bool :send-fn f}`; with SMTP credentials unset `configured?` is false and the outbox keeps
  messages queued."
  (:require [integrant.core :as ig])
  (:import (jakarta.mail Message$RecipientType Session Transport)
           (jakarta.mail.internet InternetAddress MimeBodyPart MimeMessage MimeMultipart)
           (java.util Properties)))

(defn- smtp-session
  "Builds a Jakarta Mail session for the SMTP settings map `{:host :port}` (STARTTLS, or implicit TLS on 465)."
  ^Session [{:keys [host port]}]
  (let [p (Properties.)]
    (.put p "mail.transport.protocol" "smtp")
    (.put p "mail.smtp.host" host)
    (.put p "mail.smtp.port" (str port))
    (.put p "mail.smtp.auth" "true")
    (.put p "mail.smtp.connectiontimeout" "10000")
    (.put p "mail.smtp.timeout" "20000")
    (.put p "mail.smtp.writetimeout" "20000")
    (if (= 465 port)
      (.put p "mail.smtp.ssl.enable" "true")
      (do (.put p "mail.smtp.starttls.enable" "true")
          (.put p "mail.smtp.starttls.required" "true")))
    (Session/getInstance p)))

(defn- smtp-send!
  "Sends `{:from :to :subject :text :html}` using the smtp config; throws on any SMTP failure."
  [smtp {:keys [from to subject text html]}]
  (let [session (smtp-session smtp)
        msg (MimeMessage. session)
        alt (MimeMultipart. "alternative")]
    (.setFrom msg (InternetAddress. ^String (or from (:user smtp))))
    (.setRecipient msg Message$RecipientType/TO (InternetAddress. ^String to))
    (.setSubject msg ^String subject "UTF-8")
    (let [t (MimeBodyPart.)] (.setText t ^String text "UTF-8") (.addBodyPart alt t))
    (when html
      (let [h (MimeBodyPart.)] (.setContent h ^String html "text/html; charset=UTF-8") (.addBodyPart alt h)))
    (.setContent msg alt)
    (with-open [tr (.getTransport session "smtp")]
      (.connect ^Transport tr ^String (:host smtp) ^Integer (:port smtp) ^String (:user smtp) ^String (:password smtp))
      (.sendMessage ^Transport tr msg (.getAllRecipients msg)))
    true))

(defn configured?
  "True when the mailer has SMTP credentials and can attempt delivery."
  [mailer]
  (boolean (:configured? mailer)))

(defn send!
  "Sends one email `{:from :to :subject :text :html}` through `mailer`. Returns true on success.
  Side effect: network I/O to the SMTP server. Throws when not configured or when delivery fails."
  [mailer message]
  ((:send-fn mailer) message))

(defmethod ig/init-key :aurashots/mailer [_ {:keys [config send-fn]}]
  (let [smtp (:smtp config)
        ok? (boolean (and (:user smtp) (:password smtp)))]
    {:configured? (or ok? (some? send-fn))
     :send-fn (or send-fn
                  (if ok?
                    #(smtp-send! smtp %)
                    (fn [_] (throw (ex-info "Email is not configured" {})))))}))
