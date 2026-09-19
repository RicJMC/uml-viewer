(ns uml-viewer.python-language.graph-python
  (:require [clojure.java.io :as io]
            [uml-viewer.graph :as graph]
            [uml-viewer.python-language.bridge :as bridge]))

(defrecord PythonGraph []
  graph/LanguageGraph
  (scan [_ root options]
    (let [result (bridge/invoke "scan" root (or (:prefix options) ""))]
      (doseq [warning (:warnings result)]
        (binding [*out* *err*] (println "Python:" warning)))
      (assoc result :lang :python
                    :source-root (.getCanonicalPath (io/file root))
                    :source-prefix (:prefix options)
                    :metrics-mode :verified))))

(def impl (->PythonGraph))
(graph/register! :python impl)
