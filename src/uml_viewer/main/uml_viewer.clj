(ns uml-viewer.main.uml-viewer
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.clojure-language.source-clojure :as clj-source]
            [uml-viewer.cpp-language.source-cpp]
            [uml-viewer.csharp-language.source-csharp]
            [uml-viewer.domain.log :as log]
            [uml-viewer.go-language.source-go]
            [uml-viewer.java-language.source-java]
            [uml-viewer.haskell-language.source-haskell]
            [uml-viewer.kotlin-language.source-kotlin]
            [uml-viewer.main.ir-generator :as generator]
            [uml-viewer.python-language.source-python]
            [uml-viewer.python-language.source-python-external]
            [uml-viewer.ruby-language.source-ruby]
            [uml-viewer.rust-language.source-rust]
            [uml-viewer.source :as source]
            [uml-viewer.typescript-language.source-typescript])
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

(defn setsid-bin
  "util-linux setsid, when present. The detached viewer must leave the
   launcher's session: otherwise the kernel SIGHUPs it when the foreground
   launcher exits and the window never opens."
  []
  (first (filter #(.canExecute (io/file %))
                 ["/usr/bin/setsid" "/bin/setsid"])))

(defn detach-command [args]
  (vec (concat (when-let [setsid (setsid-bin)] [setsid])
               [(java-bin) "-cp" (System/getProperty "java.class.path")
                "clojure.main" "-m" "uml-viewer.main.uml-viewer"]
               (keep identity args))))

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

(defrecord DocumentSource [lang impl]
  source/LanguageSource
  (locate [_ ident] (source/locate impl ident))
  (extract [_ text ident] (source/extract impl text ident))
  (start-line [_ text ident] (source/start-line impl text ident))
  (title [_ ident] (source/title impl ident)))

(defn source-for [document]
  (let [lang (keyword (or (:lang document) :clojure))]
    (->DocumentSource lang (or (source/lookup lang) clj-source/impl))))

(defn regenerate! [path]
  (let [document (edn/read-string (slurp path))
        policy-path (:policy-file document)
        policy (ir-generator/read-policy policy-path)]
    (ir-generator/generate (generator/graph-for policy) policy-path path)))

(defn -main [& args]
  (when (should-detach? args)
    (let [p (detach! args)]
      (println "UML viewer started (pid" (.pid p) "). Log:" log/log-name)
      (System/exit 0)))
  (log/install-exception-log!)
  (let [options (core/parse-args args)
        document (when-not (:help? options)
                   (try
                     (edn/read-string (slurp (:path options)))
                     (catch Exception _ nil)))]
    (swap! sketch/!bridge assoc :standalone? (boolean (:standalone? options)))
    (when (:standalone? options)
      (swap! sketch/!bridge assoc :keep-agent true
             :regenerate #(regenerate! (:path options))))
    (apply core/start! (if document (source-for document) clj-source/impl) args)))
