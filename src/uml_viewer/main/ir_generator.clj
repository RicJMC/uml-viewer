(ns uml-viewer.main.ir-generator
  (:require [uml-viewer.clojure-language.graph-clojure :as clj-graph]
            [uml-viewer.python-language.graph-python :as python-graph]
            [uml-viewer.application.ir-generator :as ir-generator])
  (:gen-class))

(defn graph-for [policy]
  (case (or (:lang policy) :clojure)
    :clojure clj-graph/impl
    :python python-graph/impl
    (throw (ex-info "Unsupported policy language" {:lang (:lang policy)}))))

(defn -main [& args]
  (let [path (or (first args) "examples/uml-viewer.policy.edn")
        policy (ir-generator/read-policy path)
        implementation (graph-for policy)]
    (println "Wrote" (ir-generator/generate implementation path (second args)))))
