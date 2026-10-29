(ns aurashots.features.enquiries.core
  "Slice `enquiries`, pure logic (architecture.md section 2.3): builds enquiry records and their admin views. No I/O."
  (:require [aurashots.shared.whatsapp :as whatsapp]))

(defn build-enquiry
  "Builds a new enquiry record (status :new) from validated `body` `{:name :email :phone :message}`."
  [body id now]
  {:id id :name (:name body) :email (:email body) :phone (:phone body) :message (:message body)
   :receivedAt now :status :new})

(defn answer
  "Marks an enquiry answered. Already answered enquiries are returned unchanged."
  [e]
  (assoc e :status :answered))

(defn admin-view
  "Builds the admin list item including a prefilled WhatsApp link when a phone number is present."
  [e]
  {:id (:id e) :name (:name e) :email (:email e) :phone (:phone e) :message (:message e)
   :receivedAt (:receivedAt e) :status (name (:status e))
   :whatsappLink (when (:phone e)
                   (whatsapp/link (:phone e) (str "Hello " (:name e) ", this is Aura Shots replying to your message.")))})
