;; Starts the backend with the production JVM flags (architecture.md section 8): heap capped at 512 MB and the
;; flags Datalevin needs on recent JDKs. Usage: bb scripts/backend/run.clj  (or: java <flags> -jar target/aurashots.jar)
(require '[babashka.process :as p])
(p/shell "clojure" "-J-Xmx512m" "-J--add-opens=java.base/java.nio=ALL-UNNAMED" "-J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
         "-J--enable-native-access=ALL-UNNAMED" "-M:run")
