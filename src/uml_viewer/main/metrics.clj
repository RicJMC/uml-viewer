(ns uml-viewer.main.metrics
  "Import coverage or mutation reports into .metrics/."
  (:require [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.application.metrics :as metrics]
            [uml-viewer.languages.external])
  (:gen-class))

(defn- policy-of [path]
  (ir-generator/read-policy (or path "uml-viewer.policy.edn")))

(defn -main [& args]
  (let [[cmd report policy-path] args
        policy (policy-of policy-path)
        root (System/getProperty "user.dir")
        info {:lang (:lang policy) :src (:src policy) :prefix (:prefix policy)}]
    (case cmd
      "coverage" (println "Wrote" (metrics/import-coverage! root info report))
      "mutate" (do (metrics/import-mutation! root info report)
                   (println "Wrote" (str root "/.metrics/mutate")))
      (do (println "usage: metrics coverage <file> [policy.edn]")
          (println "       metrics mutate <file> [policy.edn]")
          (System/exit 2)))))
