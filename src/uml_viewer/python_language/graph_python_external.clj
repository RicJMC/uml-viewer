(ns uml-viewer.python-language.graph-python-external
  "Deep Python scan through the uml_viewer_python package.

  The built-in `:python` scanner needs no extra tool and is the default.
  This scanner is opt-in (`:lang :python-external`): it reports members
  with complexity and source spans, so `metrics/write-static!` can seed
  `.metrics/crap.edn` before coverage is imported."
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
(graph/register! :python-external impl)
