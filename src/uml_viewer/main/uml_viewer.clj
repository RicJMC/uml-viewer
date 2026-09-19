(ns uml-viewer.main.uml-viewer
  (:require [clojure.edn :as edn]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.clojure-language.source-clojure :as clj-source]
            [uml-viewer.python-language.source-python :as python-source]
            [uml-viewer.main.ir-generator :as generator]
            [uml-viewer.application.ir-generator :as ir-generator])
  (:gen-class))

(defn source-for [document]
  (case (or (:lang document) :clojure)
    :clojure clj-source/impl
    :python (python-source/create (:source-root document) (:source-prefix document))
    (throw (ex-info "Unsupported document language" {:lang (:lang document)}))))

(defn regenerate! [path]
  (let [document (edn/read-string (slurp path))
        policy-path (:policy-file document)
        policy (ir-generator/read-policy policy-path)]
    (ir-generator/generate (generator/graph-for policy) policy-path path)))

(defn -main [& args]
  (let [options (core/parse-args args)
        document (when-not (:help? options)
                   (try
                     (edn/read-string (slurp (:path options)))
                     (catch Exception _ nil)))]
    (when (:standalone? options)
      (swap! sketch/!bridge assoc :keep-agent true
             :regenerate #(regenerate! (:path options))))
    (apply core/start! (if document (source-for document) clj-source/impl) args)))
