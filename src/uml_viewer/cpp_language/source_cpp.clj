(ns uml-viewer.cpp-language.source-cpp
  "Direct source-location support for clang-uml records; no C++ parsing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.source :as source]))

(defn- source-file [ident]
  (let [file (:file ident)
        root (:root ident)]
    (when (and file root)
      (.getPath (.getCanonicalFile (io/file root file))))))

(defrecord CppSource []
  source/LanguageSource
  (locate [_ ident]
    (let [path (source-file ident)]
      (when (and path (.isFile (io/file path))) path)))
  (extract [_ text ident]
    (let [line (:line ident)
          lines (str/split text #"\r?\n" -1)]
      (when (and (number? line) (pos? line) (<= line (count lines)))
        (nth lines (dec line)))))
  (start-line [_ _ ident]
    (let [line (:line ident)]
      (when (and (number? line) (pos? line)) line)))
  (title [_ ident]
    (str (or (source-file ident) (:file ident) "C++ source")
         (when (:line ident) (str ":" (:line ident))))))

(def impl (->CppSource))
(source/register! :cpp impl)
