(ns uml-viewer.python-language.source-python
  (:require [clojure.string :as str]
            [uml-viewer.source :as source]
            [uml-viewer.python-language.bridge :as bridge]))

(defrecord PythonSource [root prefix selected]
  source/LanguageSource
  (locate [_ identity]
    (let [span (bridge/invoke "source" root prefix
                              "--namespace" (str (:ns identity))
                              "--name" (str (:name identity)))]
      (reset! selected span)
      (:file span)))
  (extract [_ text _]
    (when-let [span @selected]
      (let [lines (str/split-lines text)
            first-line (dec (:line span))
            last-line (:end-line span)]
        (str/join "\n" (subvec (vec lines) first-line last-line)))))
  (start-line [_ _ _] (:line @selected))
  (title [_ identity] (str (:ns identity) "." (:name identity))))

(defn create [root prefix]
  (assoc (->PythonSource root prefix (atom nil)) :lang :python))
