(ns uml-viewer.main.ir-generator
  (:require [clojure.java.io :as io]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.clojure-language.graph-clojure]
            [uml-viewer.csharp-language.graph-csharp]
            [uml-viewer.go-language.graph-go]
            [uml-viewer.graph :as graph]
            [uml-viewer.haskell-language.graph-haskell]
            [uml-viewer.kotlin-language.graph-kotlin]
            [uml-viewer.languages.external]
            [uml-viewer.python-language.graph-python]
            [uml-viewer.python-language.graph-python-external]
            [uml-viewer.ruby-language.graph-ruby]
            [uml-viewer.rust-language.graph-rust]
            [uml-viewer.typescript-language.graph-typescript])
  (:gen-class))

(defn graph-for
  "Language graph selected by the policy, or nil when no scanner is installed."
  [policy]
  (let [lang (keyword (or (:lang policy) :clojure))]
    (or (graph/lookup lang)
        (throw (ex-info (str "no LanguageGraph for " lang) {:lang lang})))))

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

(defn- generation-plan
  "Policy path, output path, and language graph chosen from `args`.
  Does not write a file."
  [args]
  (let [policy-path (or (first args) (default-policy))
        policy (ir-generator/read-policy policy-path)
        lang (keyword (or (:lang policy) :clojure))]
    {:policy-path policy-path
     :out (or (second args) (:out policy) "examples/uml-viewer.edn")
     :impl (or (graph/lookup lang)
               (throw (ex-info (str "no LanguageGraph for " lang) {:lang lang}))) }))

(defn -main [& args]
  (let [report? (boolean (some #{"--report"} args))
        args (remove #{"--report"} args)
        {:keys [policy-path out impl]} (generation-plan args)
        {:keys [out doc]} (ir-generator/generate-with-doc impl policy-path out)]
    (println "Wrote" out)
    (when report?
      (doseq [line (ir-generator/report doc)]
        (println line)))))
