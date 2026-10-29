;; Memory check (fitness check F40, architecture.md section 8). Meant to be run on the real host by deployment-engineer
;; (and locally as a smoke test): starts the uberjar with the production JVM flags (-Xmx512m and the add-opens flags),
;; drives a burst of mixed public requests plus one 6000 px upload, and reports the peak resident set size (RSS, read
;; from `ps -o rss=`, the figure the host's memory limit applies to) against a limit.
;;
;; Usage: bb memory-check [--limit-mb 1024] [--budget-mb 800] [--seconds 30] [--workers 12] [--jar PATH]
;;                        [--data-dir DIR] [--keep]
;;   --limit-mb   hard limit, default 1024 (a 1 GB host): peak RSS above it is FAIL
;;   --budget-mb  design budget, default 800 (architecture.md section 8): peak RSS above it is WARN
;;   --seconds    length of the request burst (default 30; the full F40 run uses 300)
;;   --workers    concurrent readers (default 12; the full F40 run uses 20)
;;   --jar        the uberjar (default target/aurashots.jar; build it with `clojure -T:build uber`)
;;   --data-dir   data directory to run against (default: a fresh temporary one with the seed content; point it at a
;;                COPY of the real data on the host to include the real database in the measurement)
;; The server runs on a spare port with no mail configured and never touches the live data dir. Exit code 0 on PASS or
;; WARN, 1 on FAIL.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[babashka.http-client :as http]
         '[cheshire.core :as json] '[clojure.string :as str])

(defn parse-args
  "Turns `--flag value` pairs into a keyword map; `--keep` is a boolean flag."
  [args]
  (loop [[a & more] args, out {}]
    (cond (nil? a) out
          (= "--keep" a) (recur more (assoc out :keep true))
          (str/starts-with? a "--") (recur (rest more) (assoc out (keyword (subs a 2)) (first more)))
          :else (recur more out))))

(def opts (parse-args *command-line-args*))
(def limit-mb (parse-long (or (:limit-mb opts) "1024")))
(def budget-mb (parse-long (or (:budget-mb opts) "800")))
(def seconds (parse-long (or (:seconds opts) "30")))
(def workers (parse-long (or (:workers opts) "12")))
(def jar (or (:jar opts) "target/aurashots.jar"))
(when-not (fs/exists? jar)
  (println (str "FAIL: " jar " not found. Build it with: clojure -T:build uber")) (System/exit 1))

(def prod-flags
  "The production JVM flags (architecture.md section 8)."
  ["-Xmx512m" "--add-opens=java.base/java.nio=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
   "--enable-native-access=ALL-UNNAMED"])

(def tmp (str (fs/create-temp-dir {:prefix "aurashots-mem-"})))
(def data-dir (or (:data-dir opts) (str (fs/path tmp "data"))))
(def port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))
(def base (str "http://localhost:" port))
(def env (merge (into {} (System/getenv))
                {"APP_ENV" "dev" "DATA_DIR" data-dir "UPLOAD_DIR" "" "BACKUP_DIR" "" "PORT" (str port) "PUBLIC_BASE_URL" base
                 "TRUSTED_PROXY_HOPS" "1" "SMTP_USER" "" "SMTP_PASSWORD" "" "OWNER_EMAIL" "" "TOKEN_PEPPER" ""}))

(defn rss-mb
  "Resident set size of process `pid` in MB, from `ps -o rss=` (the figure F40 names; includes memory-mapped database
  pages). Returns nil when the process is gone."
  [pid]
  (try (let [r (p/shell {:out :string :err :string :continue true} "ps" "-o" "rss=" "-p" (str pid))
             kb (parse-long (str/trim (:out r)))]
         (when kb (/ kb 1024.0)))
       (catch Exception _ nil)))

(defn run!*
  "Runs the whole measurement; returns the result map."
  []
  (let [log (str (fs/path tmp "server.log"))
        run-cli (fn [& cli] (apply p/shell {:out :string :err :string :continue true :extra-env env}
                                   (concat ["java"] prod-flags ["-cp" jar "clojure.main" "-m" "aurashots.cli"] cli)))
        ;; an owner account to authorise the upload (created on the temporary data dir, before the server starts)
        link (when-not (:data-dir opts)
               (->> (str/split-lines (:out (run-cli "create-owner" "--name" "Memory Check" "--email" "memory-check@example.test")))
                    (filter #(str/includes? % "token=")) first))
        server (apply p/process {:out log :err :out :extra-env env} (concat ["java"] prod-flags ["-jar" jar]))
        pid (.pid ^Process (:proc server))
        peak (atom 0.0)
        samples (atom [])
        sampling? (atom true)
        sampler (future (while @sampling?
                          (when-let [m (rss-mb pid)] (swap! peak max m) (swap! samples conj m))
                          (Thread/sleep 300)))
        get* #(http/get (str base %) {:throw false :timeout 30000})]
    (try
      (let [deadline (+ (System/currentTimeMillis) 120000)]
        (loop []
          (when-not (try (= 200 (:status (get* "/api/health"))) (catch Exception _ false))
            (when (or (> (System/currentTimeMillis) deadline) (not (.isAlive ^Process (:proc server))))
              (throw (ex-info (str "the server did not start, see " log) {})))
            (Thread/sleep 400) (recur))))
      (let [idle (rss-mb pid)
            slugs (mapv :slug (:items (json/parse-string (:body (get* "/api/services")) true)))
            product-slugs (mapv :slug (:items (json/parse-string (:body (get* "/api/products")) true)))
            images (mapv #(get-in % [:image :id]) (:items (json/parse-string (:body (get* "/api/portfolio")) true)))
            reads (vec (concat ["/" "/services" "/shop" "/portfolio" "/about" "/contact" "/legal/privacy" "/sitemap.xml" "/robots.txt"
                                "/api/site-info" "/api/services" "/api/products" "/api/portfolio" "/api/documents/privacy"
                                "/api/availability?from=2027-02-01&to=2027-02-07"
                                "/api/availability?from=2027-02-01&to=2027-03-15"]
                               (map #(str "/services/" %) slugs) (map #(str "/api/services/" %) slugs)
                               (map #(str "/shop/" %) product-slugs) (map #(str "/api/products/" %) product-slugs)
                               (mapcat (fn [i] [(str "/media/" i "/card.jpg") (str "/media/" i "/large.jpg") (str "/media/" i "/thumb.jpg")]) images)))
            ok (atom 0) bad (atom []) latencies (atom [])
            one (fn [path]
                  (let [t0 (System/nanoTime)
                        r (try (if (= 0 (rand-int 12))
                                 (http/post (str base "/api/quotes/sessions")
                                            {:throw false :timeout 30000 :headers {"content-type" "application/json"}
                                             :body (json/generate-string {:serviceIds (vec (take 2 (shuffle ["seed-service-single-session" "seed-service-couple-session"])))})})
                                 (get* path))
                               (catch Exception e {:status (str e)}))]
                    (swap! latencies conj (/ (- (System/nanoTime) t0) 1e6))
                    (if (and (number? (:status r)) (< (:status r) 400)) (swap! ok inc) (swap! bad conj [path (:status r)]))))
            end (+ (System/currentTimeMillis) (* 1000 seconds))
            burst (doall (for [_ (range workers)]
                           (future (while (< (System/currentTimeMillis) end) (one (rand-nth reads))))))
            ;; one 6000 px upload in the middle of the burst
            upload (future
                     (Thread/sleep (* 500 seconds))
                     (try
                       (when-not link (throw (ex-info "skipped: --data-dir given, no owner created" {})))
                       (let [gen (str (fs/path tmp "Gen.java")) img (str (fs/path tmp "big.jpg"))]
                         (spit gen "import javax.imageio.*; import java.awt.image.*; import java.io.*;
public class Gen { public static void main(String[] a) throws Exception { int w=6000,h=4000;
 BufferedImage im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
 for (int y=0;y<h;y++) for (int x=0;x<w;x++) { int r=(int)(127+120*Math.sin(x/300.0)), g=(int)(127+120*Math.cos(y/250.0)), b=(x+y)/40%256; im.setRGB(x,y,(r<<16)|(g<<8)|b); }
 ImageIO.write(im,\"jpg\",new File(a[0])); } }")
                         (p/shell {:out :string :err :string} "java" gen img)
                         (let [token (second (re-find #"token=(.*)$" link))
                               json-post (fn [path body & [h]] (http/post (str base path) {:throw false :headers (merge {"content-type" "application/json"} h)
                                                                                           :body (json/generate-string body)}))
                               _ (json-post "/api/admin/auth/reset-password" {:token token :newPassword "memory check password 1"})
                               login (json-post "/api/admin/auth/login" {:email "memory-check@example.test" :password "memory check password 1"})
                               cookie (second (re-find #"session=([^;]+)" (str (get-in login [:headers "set-cookie"]))))
                               csrf (:csrfToken (json/parse-string (:body login) true))
                               r (http/post (str base "/api/admin/images")
                                            {:throw false :timeout 120000 :headers {"cookie" (str "session=" cookie) "x-csrf-token" csrf}
                                             :multipart [{:name "file" :content (fs/file img) :file-name "big.jpg"}]})]
                           {:status (:status r) :bytes (fs/size img)}))
                       (catch Exception e {:status :skipped :why (ex-message e)})))]
        (run! deref burst)
        (let [up @upload]
          (Thread/sleep 1500)
          (let [after (rss-mb pid) lat (sort @latencies) n (count lat)]
            {:idle-mb idle :peak-mb @peak :final-mb after :requests (+ @ok (count @bad)) :errors (count @bad)
             :error-sample (take 5 (frequencies @bad)) :rps (/ (+ @ok (count @bad)) (double seconds))
             :p95-ms (when (pos? n) (nth lat (min (dec n) (int (* 0.95 n))))) :upload up})))
      (finally
        (reset! sampling? false)
        (.destroy ^Process (:proc server))
        (deref server 30000 nil)
        (when (.isAlive ^Process (:proc server)) (.destroyForcibly ^Process (:proc server)))
        (when-not (:keep opts) nil)))))

(println (str "Memory check: uberjar " jar ", flags " (str/join " " prod-flags)))
(println (str "  burst: " workers " workers for " seconds " s on port " port "; limit " limit-mb " MB, design budget " budget-mb " MB"))
(def result
  (try (run!*)
       (catch Throwable t (println "FAIL:" (ex-message t)) (when-not (:keep opts) (fs/delete-tree tmp)) (System/exit 1))))
(if (:keep opts) (println "Kept" tmp) (fs/delete-tree tmp))
(let [{:keys [idle-mb peak-mb final-mb requests errors rps p95-ms upload error-sample]} result
      f #(format "%.0f MB" (double %))
      status (cond (> peak-mb limit-mb) :fail (> peak-mb budget-mb) :warn :else :pass)]
  (println (str "  RSS idle after start : " (f idle-mb)))
  (println (str "  RSS peak             : " (f peak-mb)))
  (println (str "  RSS at the end       : " (f final-mb)))
  (println (format "  requests             : %d (%.0f/s), %d errors, p95 %.0f ms" requests rps errors (double (or p95-ms 0))))
  (when (pos? errors) (println "  error sample         :" (pr-str error-sample)))
  (println (str "  6000 px upload       : " (pr-str upload)))
  (println (case status
             :pass (str "PASS: peak " (f peak-mb) " is within the design budget of " budget-mb " MB (limit " limit-mb " MB)")
             :warn (str "WARN: peak " (f peak-mb) " is above the design budget of " budget-mb " MB but within the limit of " limit-mb " MB")
             :fail (str "FAIL: peak " (f peak-mb) " is above the limit of " limit-mb " MB")))
  (when (or (= :fail status) (pos? errors)) (System/exit 1)))
