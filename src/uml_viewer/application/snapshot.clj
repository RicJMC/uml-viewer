(ns uml-viewer.application.snapshot
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- digest [file]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath file))
        algorithm (java.security.MessageDigest/getInstance "SHA-256")
        result (.digest algorithm bytes)]
    (format "%064x" (java.math.BigInteger. 1 result))))

(defn- unchanged? [{:keys [path sha256]}]
  (let [file (io/file path)]
    (and (.isFile file) (= sha256 (digest file)))))

(defn- source-files [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".py")))
       (map #(.getPath %))
       set))

(defn current? [manifest]
  (try
    (and (= (set (:python-files manifest))
            (source-files (:source-root manifest)))
         (every? unchanged? (:source-files manifest)))
    (catch Exception _ false)))

(defn load-state [root]
  (let [file (io/file root ".metrics/manifest.edn")
        updating (io/file root ".metrics/updating")]
    (cond
      (.exists updating) {:status "updating"}
      (not (.exists file)) {:status "unverified"}
      :else
      (try
        (let [manifest (edn/read-string (slurp file))]
          {:manifest manifest
           :status (if (current? manifest) "current" "stale")})
        (catch Exception _ {:status "stale"})))))

(def metric-keys
  [:crap :cc :coverage :killed :survived :uncovered :sites
   :mutation-status :mutation-counts])

(defn clear-class [component]
  (let [clear (fn [member] (apply dissoc member metric-keys))]
    (-> (clear component)
        (assoc :metrics-status "missing")
        (update :ops #(mapv (comp (fn [op] (assoc op :metrics-status "missing")) clear) %)))))

(defn matches? [document metrics]
  (and (= "current" (:status metrics))
       (= (:source-root document) (get-in metrics [:manifest :source-root]))))

(defn mark-current [component]
  (-> component
      (assoc :metrics-status "current")
      (update :ops #(mapv (fn [op] (assoc op :metrics-status "current")) %))))

(defn map-classes [document transform]
  (cond
    (:hierarchical document) (update document :classes #(mapv transform %))
    (:diagrams document) (update document :diagrams #(mapv (fn [diagram]
                                                           (map-classes diagram transform)) %))
    (:packages document) (update document :packages
                                 #(mapv (fn [package]
                                          (update package :classes
                                                  (fn [classes] (mapv transform classes)))) %))
    :else document))
