(ns aurashots.features.notifications.core
  "Slice `notifications`, pure logic (architecture.md section 2.3): template substitution, message records,
  the retry schedule and the contract `Message` view. No I/O; template text is passed in."
  (:require [clojure.string :as str]))

(def backoff-ms
  "Delays before retry after the 1st, 2nd, ... 5th failed attempt: 1 min, 5 min, 30 min, 2 h, 12 h
  (architecture.md section 5, Email). A 6th failure marks the message failed."
  [60000 300000 1800000 7200000 43200000])

(defn substitute
  "Replaces each `{{name}}` in `tpl` with the value of `name` in `vars` (missing values become the empty string)."
  [tpl vars]
  (str/replace tpl #"\{\{\s*([A-Za-z0-9]+)\s*\}\}"
               (fn [[_ k]] (str (get vars (keyword k) "")))))

(defn render
  "Renders a template file body `tpl` whose first line is `Subject: ...` followed by a blank line and the text.
  Returns `{:subject s :text t}`."
  [tpl vars]
  (let [out (substitute tpl vars)
        [head & more] (str/split out #"\r?\n" 2)
        subject (str/trim (str/replace (or head "") #"^Subject:\s*" ""))]
    {:subject subject :text (str/trim (str/replace (or (first more) "") #"^\s*\n" ""))}))

(defn- escape-html
  "Escapes `s` for HTML text."
  [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn text->html
  "Wraps plain `text` in a minimal HTML document: paragraphs for blank-line separated blocks, line breaks within,
  and bare http(s) links made clickable. All user text is escaped."
  [text]
  (let [link (fn [s] (str/replace s #"https?://[^\s<]+" #(str "<a href=\"" % "\">" % "</a>")))
        paras (map (fn [p] (str "<p>" (str/join "<br>" (map (comp link escape-html) (str/split-lines p))) "</p>"))
                   (str/split text #"\n\s*\n"))]
    (str "<!doctype html><html><body style=\"font-family:Arial,sans-serif;line-height:1.5;color:#222\">"
         (str/join paras) "</body></html>")))

(defn new-message
  "Builds a queued message record. `m` has `:id :to :template :subject :text :subject-id`; `now` is an `Instant`."
  [{:keys [id to template subject text subject-id]} now]
  {:id id :channel "email" :template (name template) :to to :subject subject :text text
   :html (text->html text) :subjectId subject-id :status :queued :attempts 0
   :nextAttemptAt now :sentAt nil :error nil :createdAt now})

(defn view
  "Builds the contract `Message` map from a stored message record."
  [m]
  (cond-> {:id (:id m) :channel "email" :template (:template m) :to (:to m)
           :sentAt (:sentAt m) :status (name (:status m))}
    (:error m) (assoc :error (:error m))))

(defn after-attempt
  "Returns the message record updated after a send attempt at `now` (an Instant). `error` is nil on success or an
  error message string; failures schedule a retry per `backoff-ms` or mark the message failed after the 6th."
  [m error now]
  (let [attempts (inc (:attempts m))]
    (cond
      (nil? error) (assoc m :status :sent :sentAt now :attempts attempts :error nil)
      (<= attempts (count backoff-ms))
      (assoc m :status :queued :attempts attempts :error error
             :nextAttemptAt (.plusMillis ^java.time.Instant now (nth backoff-ms (dec attempts))))
      :else (assoc m :status :failed :attempts attempts :error error))))

(defn requeue
  "Resets `m` to be sent again immediately (staff pressed \"Send again\")."
  [m now]
  (assoc m :status :queued :attempts 0 :nextAttemptAt now :error nil))
