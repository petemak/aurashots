(ns build
  "Builds the backend uberjar with tools.build: `clojure -T:build uber` writes `target/aurashots.jar`
  (main class `aurashots.system`). The manifest carries `Add-Opens` and `Enable-Native-Access`, which Datalevin needs
  on recent JDKs, so `java -Xmx512m -jar target/aurashots.jar` runs without further flags (only the heap cap is a
  command-line flag). scripts/backend/run.clj starts the same configuration from source."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/aurashots.jar")

(defn uber
  "Compiles the application and writes the uberjar."
  [_]
  (b/delete {:path "target"})
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :ns-compile '[aurashots.system aurashots.cli] :class-dir class-dir})
    (b/uber {:class-dir class-dir :uber-file uber-file :basis basis :main 'aurashots.system
             :manifest {"Add-Opens" "java.base/java.nio java.base/sun.nio.ch"
                        "Enable-Native-Access" "ALL-UNNAMED"}})))
