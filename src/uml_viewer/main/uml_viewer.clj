(ns uml-viewer.main.uml-viewer
  (:require [clojure.java.io :as io]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.clojure-language.source-clojure :as clj-source]
            [uml-viewer.domain.log :as log])
  (:import (java.io File)
           (java.lang ProcessBuilder ProcessBuilder$Redirect))
  (:gen-class))

(def foreground-env "UML_VIEWER_FOREGROUND")

(defn help-arg? [args]
  (boolean (some #{"-h" "--help"} args)))

(defn should-detach?
  "True when launched from a terminal and not already the background JVM."
  ([args] (should-detach? args (System/getenv foreground-env) (System/console)))
  ([args env-val console]
   (and (not (help-arg? args))
        (nil? env-val)
        (some? console))))

(defn- java-bin []
  (str (System/getProperty "java.home") File/separator "bin" File/separator "java"))

(defn detach-command [args]
  (into [(java-bin) "-cp" (System/getProperty "java.class.path")
         "clojure.main" "-m" "uml-viewer.main.uml-viewer"]
        (keep identity args)))

(defn detach!
  "Spawn a child JVM whose stdout/stderr append to the viewer log."
  [args]
  (let [log (io/file log/log-name)
        pb (ProcessBuilder. (into-array String (detach-command args)))]
    (.redirectOutput pb (ProcessBuilder$Redirect/appendTo log))
    (.redirectError pb (ProcessBuilder$Redirect/appendTo log))
    (.redirectInput pb (ProcessBuilder$Redirect/from (io/file "/dev/null")))
    (.put (.environment pb) foreground-env "1")
    (.start pb)))

(defn -main [& args]
  (when (should-detach? args)
    (let [p (detach! args)]
      (println "UML viewer started (pid" (.pid p) "). Log:" log/log-name)
      (System/exit 0)))
  (log/install-exception-log!)
  (try
    (apply core/start! clj-source/impl args)
    (catch Throwable t
      (log/log-exception! t "start!")
      (System/exit 1))))
