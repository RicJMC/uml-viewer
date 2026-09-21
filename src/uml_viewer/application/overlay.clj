(ns uml-viewer.application.overlay
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.application.snapshot :as snapshot]))

(defn- read-edn [f]
  (when (and f (.exists (io/file f)))
    (edn/read-string (slurp f))))

(defn load-crap
  ([root] (load-crap root ".metrics/crap.edn"))
  ([root rel]
   (let [data (read-edn (io/file root rel))]
     (group-by :namespace (or (:entries data) [])))))

(defn- mutate-files [root]
  (let [dir (io/file root ".metrics" "mutate")]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))))))

(defn- ns-from-source [src]
  (when src
    (-> src
        (str/replace #"\\" "/")
        (str/replace #"^src/" "")
        (str/replace #"\.[^.]+$" "")
        (str/replace #"/" ".")
        (str/replace #"_" "-"))))

(defn load-mutate
  [root]
  (reduce (fn [acc f]
            (if-let [data (read-edn f)]
              (let [ns-name (or (:namespace data) (ns-from-source (:source data)))]
                (cond-> acc ns-name (assoc ns-name data)))
              acc))
          {}
          (or (mutate-files root) [])))

(defn metrics-root
  "Directory that contains `.metrics`, walking up from `path` (file or dir).
  Falls back to user.dir when none is found."
  [path]
  (loop [dir (let [f (io/file path)]
               (cond
                 (nil? path) (io/file (System/getProperty "user.dir"))
                 (.isFile f) (.getParentFile (.getCanonicalFile f))
                 :else (.getCanonicalFile f)))]
    (cond
      (nil? dir) (System/getProperty "user.dir")
      (.isDirectory (io/file dir ".metrics")) (.getPath dir)
      :else (recur (.getParentFile dir)))))

(defn load-metrics
  ([] (load-metrics (System/getProperty "user.dir")))
  ([root]
   (let [state (snapshot/load-state root)
         readable? (#{"current" "unverified"} (:status state))]
     (merge state
            {:crap (if readable? (or (load-crap root) {}) {})
             :mutate (if readable? (or (load-mutate root) {}) {})}))))

(defn metrics-stamp
  "Fingerprint of score files and their publication state."
  [root]
  (let [snapshots (map #(io/file root ".metrics" %)
                       ["crap.edn" "manifest.edn" "updating"])
        files (concat (filter #(.isFile %) snapshots)
                      (or (mutate-files root) []))]
    (->> files
         (map (fn [f] [(.getPath f) (.lastModified f) (.length f)]))
         sort
         vec)))

(defn- form-name [id]
  (when id
    (cond
      (str/starts-with? id "defn-/") {:name (subs id 6) :private true}
      (str/starts-with? id "defn/") {:name (subs id 5) :private false}
      :else nil)))

(defn- mutate-by-fn [snapshot]
  (into {}
        (keep (fn [form]
                (when-let [n (if (:name form)
                               (select-keys form [:name :private])
                               (form-name (:id form)))]
                  [(:name n) (assoc n
                               :killed (:killed form)
                               :survived (:survived form)
                               :uncovered (:uncovered form)
                               :mutation-status (:status form)
                               :sites (or (:sites form) (:total form)))]))
              (:forms snapshot))))

(defn class-namespace
  "Snapshot namespace for a class: authored or generated `:ns`."
  [c]
  (when-let [ns-name (:ns c)]
    (str ns-name)))

(defn- pct->ratio [cov]
  (when (number? cov)
    (/ (double cov) 100.0)))

(defn- class-crap [scores]
  (when (seq scores)
    (let [xs (mapv double scores)
          n (count xs)
          mu (/ (reduce + xs) n)
          mx (apply max xs)
          var (/ (reduce + (map #(let [d (- % mu)] (* d d)) xs)) n)
          sd (Math/sqrt var)]
      {:mu (* 0.1 (Math/round (* 10.0 mu)))
       :max (* 0.1 (Math/round (* 10.0 mx)))
       :sigma (* 0.1 (Math/round (* 10.0 sd)))})))

(defn- overlay-op [op crap-fn mut-fn]
  (cond-> (assoc op :text (or (:text op) (:name op)))
    crap-fn (assoc :cc (:complexity crap-fn)
                   :crap (:crap crap-fn)
                   :coverage (pct->ratio (:coverage crap-fn)))
    mut-fn (assoc :killed (or (:killed mut-fn) 0)
                  :survived (or (:survived mut-fn) 0)
                  :uncovered (or (:uncovered mut-fn) 0)
                  :sites (or (:sites mut-fn) 0))
    (:mutation-status mut-fn) (assoc :mutation-status (:mutation-status mut-fn))
    (or (:private op) (:private mut-fn)) (assoc :private true)))

(defn- ops-for-class [c crap-fns mut-fns]
  (let [by-name (into {} (map (juxt :name identity) crap-fns))
        names (distinct (concat (map :name (:ops c))
                                (map :name crap-fns)
                                (keys mut-fns)))]
    (mapv (fn [nm]
            (let [existing (first (filter #(= nm (:name %)) (:ops c)))
                  base (or existing {:name nm :text nm})]
              (overlay-op base (get by-name nm) (get mut-fns nm))))
          names)))

(defn overlay-class [c crap-by-ns mutate-by-ns]
  (let [ns-name (class-namespace c)
        crap-fns (or (get crap-by-ns ns-name) [])
        mut-fns (mutate-by-fn (get mutate-by-ns ns-name))
        scores (keep :crap crap-fns)
        coverages (keep :coverage crap-fns)
        statements (reduce + 0 (keep :statements crap-fns))
        covered (reduce + 0 (keep :covered crap-fns))
        ops (ops-for-class c crap-fns mut-fns)
        killed (apply + 0 (keep :killed (vals mut-fns)))
        survived (apply + 0 (keep :survived (vals mut-fns)))
        uncovered (apply + 0 (keep :uncovered (vals mut-fns)))
        sites (apply + 0 (keep :sites (vals mut-fns)))]
    (cond-> c
      (seq scores) (assoc :crap (class-crap scores)
                          :cc (apply + (map :complexity crap-fns)))
      (seq coverages) (assoc :coverage (pct->ratio
                                         (/ (reduce + coverages) (count coverages))))
      (pos? statements) (assoc :coverage (/ (double covered) statements))
      (some :mutation-status (vals mut-fns))
      (assoc :mutation-status
             (if (every? #(= "complete" (:mutation-status %)) (vals mut-fns))
               "complete" "partial"))
      (seq mut-fns) (assoc :killed killed :survived survived :uncovered uncovered
                           :sites sites)
      (seq ops) (assoc :ops ops))))

(defn- paint-packages [packages metrics]
  (mapv (fn [p]
          (update p :classes
                  (fn [cs]
                    (mapv #(overlay-class % (:crap metrics) (:mutate metrics))
                          cs))))
        packages))

(defn- paint-diagram [d metrics]
  (ir/normalize (update d :packages paint-packages metrics)))

(defn- apply-scores
  [doc metrics]
  (if (and (empty? (:crap metrics)) (empty? (:mutate metrics)))
    doc
    (cond
      (:hierarchical doc)
      (update doc :classes
              (fn [cs]
                (mapv #(overlay-class % (:crap metrics) (:mutate metrics)) cs)))
      (:diagrams doc)
      (update doc :diagrams (fn [ds] (mapv #(paint-diagram % metrics) ds)))
      (:packages doc)
      (paint-diagram doc metrics)
      :else doc)))

(defn apply-metrics [document metrics]
  (if (not= :verified (:metrics-mode document))
    (apply-scores document metrics)
    (if (snapshot/matches? document metrics)
      (apply-scores (snapshot/map-classes document snapshot/mark-current) metrics)
      (snapshot/map-classes document snapshot/clear-class))))
