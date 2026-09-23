(ns uml-viewer.main.discover
  "Write a hierarchical policy from a Python or TypeScript tree.
   Existing :levels and :proposals are kept. Nothing is invented."
  (:require [clojure.java.io :as io]
            [uml-viewer.languages.external :as external])
  (:gen-class))

(defn- default-policy [root]
  (let [example (io/file root "examples" "uml-viewer.policy.edn")
        plain (io/file root "uml-viewer.policy.edn")]
    (cond
      (.isFile example) (.getPath example)
      (.isFile plain) (.getPath plain)
      :else (.getPath plain))))

(defn discover!
  "Write `out` and `.uml-viewer/AGENT.md`. Returns the policy path."
  ([root] (discover! root (default-policy root)))
  ([root out]
   (let [lang (external/probe-lang root)]
     (when-not lang
       (throw (ex-info "no Python or TypeScript source to discover"
                       {:root (str root)})))
     (let [existing (external/read-policy-if out)
           discovered (external/discover root lang)
           policy (external/merge-policy existing discovered)]
       (external/write-policy! out policy)
       (external/write-agent-md! root lang)
       out))))

(defn -main [& args]
  (let [root (or (first args) ".")
        out (or (second args) (default-policy root))]
    (println "Wrote" (discover! root out))))
