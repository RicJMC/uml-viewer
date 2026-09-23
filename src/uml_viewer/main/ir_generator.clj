(ns uml-viewer.main.ir-generator
  (:require [clojure.java.io :as io]
            [uml-viewer.clojure-language.graph-clojure :as clj-graph]
            [uml-viewer.languages.external :as external]
            [uml-viewer.python-language.graph-python :as python-graph]
            [uml-viewer.application.ir-generator :as ir-generator])
  (:gen-class))

(defn graph-for [policy]
  (case (or (:lang policy) :clojure)
    :clojure clj-graph/impl
    :python python-graph/impl
    :typescript external/typescript-graph
    (throw (ex-info "Unsupported policy language" {:lang (:lang policy)}))))

(defn- default-policy
  "This repo keeps its policy under examples/. A discovered project writes
   uml-viewer.policy.edn in the directory you ran the generator from."
  []
  (let [example "examples/uml-viewer.policy.edn"
        plain "uml-viewer.policy.edn"]
    (cond
      (.isFile (io/file example)) example
      (.isFile (io/file plain)) plain
      :else example)))

(defn -main [& args]
  (let [path (or (first args) (default-policy))
        policy (ir-generator/read-policy path)
        implementation (graph-for policy)]
    (println "Wrote" (ir-generator/generate implementation path (second args)))))
