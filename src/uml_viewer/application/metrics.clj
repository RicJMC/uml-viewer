(ns uml-viewer.application.metrics
  "Write .metrics snapshots from analyzer member and coverage reports.
   The overlay keeps its existing EDN schema."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [uml-viewer.languages.external :as external]))

(defn crap-score
  "CRAP = CC² * (1 - cov)³ + CC. `coverage-pct` is 0–100."
  [cc coverage-pct]
  (when (and (number? cc) (number? coverage-pct))
    (let [cov (max 0.0 (min 1.0 (/ (double coverage-pct) 100.0)))
          cc (double cc)]
      (+ (* cc cc (Math/pow (- 1.0 cov) 3.0)) cc))))

(defn- entry [m]
  (let [cc (long (or (:complexity m) 1))
        cov (:coverage m)]
    (cond-> {:name (str (:name m))
             :namespace (str (or (:namespace m) (:ns m)))
             :complexity cc}
      (:private m) (assoc :private true)
      (number? cov) (assoc :coverage (double cov)
                           :crap (crap-score cc cov)))))

(defn merge-entries
  "New scan wins for complexity and privacy. Coverage stays when the new row
   has none, and CRAP is recomputed from the coverage we keep."
  [old new]
  (let [old-by (into {} (map (juxt (juxt :namespace :name) identity) (or old [])))]
    (mapv (fn [e]
            (let [prev (get old-by [(:namespace e) (:name e)])
                  cov (if (contains? e :coverage) (:coverage e) (:coverage prev))
                  row (entry (cond-> e (number? cov) (assoc :coverage cov)))]
              row))
          new)))

(defn- crap-file [root]
  (io/file root ".metrics" "crap.edn"))

(defn- read-entries [root]
  (let [f (crap-file root)]
    (when (.isFile f)
      (:entries (edn/read-string (slurp f))))))

(defn write-entries!
  [root entries]
  (let [f (crap-file root)
        merged (merge-entries (read-entries root) (mapv entry entries))]
    (.mkdirs (.getParentFile f))
    (spit f (binding [pprint/*print-right-margin* 90]
              (with-out-str (pprint/pprint {:entries merged}))))
    (.getPath f)))

(defn write-static!
  "Members from a scan, keeping coverage already stored for those names."
  [root members]
  (write-entries! root (mapv (fn [m]
                               {:name (:name m)
                                :namespace (:ns m)
                                :complexity (:complexity m)
                                :private (:private m)})
                             members)))

(defn import-coverage!
  [root {:keys [lang src prefix]} coverage-file]
  (write-entries! root (external/coverage-entries lang src prefix coverage-file)))

(defn write-mutation!
  [root snapshots]
  (doseq [snap snapshots]
    (let [rel (str/replace (:namespace snap) "." "/")
          f (io/file root ".metrics" "mutate" (str rel ".edn"))]
      (.mkdirs (.getParentFile f))
      (spit f (binding [pprint/*print-right-margin* 90]
                (with-out-str
                  (pprint/pprint {:version 2
                                  :namespace (:namespace snap)
                                  :source (:source snap)
                                  :forms (:forms snap)}))))))
  root)

(defn import-mutation!
  [root {:keys [lang src prefix]} report]
  (write-mutation! root (external/mutation-snapshots lang src prefix report)))
