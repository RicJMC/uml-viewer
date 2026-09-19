(ns uml-viewer.python-language.bridge
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]))

(defn invoke [command root prefix & arguments]
  (let [python (or (System/getenv "UML_PYTHON") "python3")
        command-line (concat [python "-B" "-m" "uml_viewer_python" command
                              "--format" "edn" "--root" (str root)
                              "--prefix" (str prefix)]
                             arguments)
        response (apply shell/sh command-line)]
    (when-not (zero? (:exit response))
      (throw (ex-info (str "Python command failed: " (:err response))
                      {:exit (:exit response)})))
    (edn/read-string (:out response))))
