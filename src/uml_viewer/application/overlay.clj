(ns uml-viewer.application.overlay
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.application.snapshot :as snapshot]
            [uml-viewer.domain.config :as config]
            [uml-viewer.domain.ir :as ir]))

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
  "One snapshot per namespace. When a dotted file and a path-under-src
  file both name that namespace, the newer file wins."
  [root]
  (reduce (fn [acc f]
            (if-let [data (read-edn f)]
              (let [ns-name (or (:namespace data) (ns-from-source (:source data)))]
                (cond-> acc ns-name (assoc ns-name data)))
              acc))
          {}
          (sort-by #(.lastModified %) (or (mutate-files root) []))))

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
  "Snapshot namespace for a class: authored or generated `:ns`,
  normalized to dots so C++ `app::detail`, PHP `App\\Foo`, and a Go
  import path join their snapshots."
  [c]
  (when-let [ns-name (:ns c)]
    (-> (str ns-name)
        (str/replace "::" ".")
        (str/replace "\\" ".")
        (str/replace #"/" "."))))

(defn- normalize-ns
  "Crapper separates Rust with `::`, names a Go package by its import path
  (`mod/internal/x`, a method's `mod/internal/x.Type`), and writes PHP
  namespaces with `\\`. All become dotted."
  [s]
  (when s
    (-> (str s)
        (str/replace #"::" ".")
        (str/replace #"/" ".")
        (str/replace #"\\" "."))))

(defn- prefix-name [prefix]
  (cond
    (nil? prefix) nil
    (keyword? prefix) (name prefix)
    :else (not-empty (str prefix))))

(defn- sole-rust-root
  "The one Rust crate class, when the tree has a single undotted Rust id."
  [classes]
  (let [roots (filterv (fn [c]
                         (and (= :rust (:lang c))
                              (not (:foreign c))
                              (when-let [id (some-> (:id c) name)]
                                (not (str/includes? id ".")))))
                       classes)]
    (when (= 1 (count roots))
      (first roots))))

(defn- claim-rank
  "Sort key when `c` should display snapshot `snap`, or nil.
  Exact `:ns` wins, then the class id (`bookwriter.model` owns `model`),
  then a dotted child (`pdf` owns `pdf.Layout`). The policy prefix, which
  is the Cargo package name, belongs to the Rust crate root."
  [c prefix rust-root snap]
  (let [ns-name (class-namespace c)
        id-name (some-> (:id c) name)]
    (cond
      (= ns-name snap) [0 0]
      (= id-name snap) [1 0]
      (and id-name (str/starts-with? snap (str id-name ".")))
      [2 (- (count id-name))]
      (and ns-name (str/starts-with? snap (str ns-name ".")))
      [2 (- (count ns-name))]
      (and rust-root (= c rust-root) prefix (= snap prefix)) [3 0]
      :else nil)))

(defn- snapshot-owner
  "Project class that displays snapshot namespace `ns-name`."
  [classes prefix ns-name]
  (let [snap (normalize-ns ns-name)
        prefix (prefix-name prefix)
        rust-root (sole-rust-root classes)]
    (->> classes
         (remove :foreign)
         (keep (fn [c]
                 (when-let [rank (claim-rank c prefix rust-root snap)]
                   [rank c])))
         (sort-by first)
         first
         second)))

(defn- owners-by-ns
  "Class `:ns` to the snapshot namespaces that class displays."
  [classes prefix snapshot-nss]
  (reduce (fn [acc snap]
            (if-let [c (snapshot-owner classes prefix snap)]
              (let [k (class-namespace c)]
                (if k
                  (update acc k (fnil conj []) snap)
                  acc))
              acc))
          {}
          snapshot-nss))

(defn- merge-mutate [snapshots]
  (let [snaps (vec (keep identity snapshots))]
    (when (seq snaps)
      {:forms (vec (mapcat #(or (:forms %) []) snaps))})))

(defn- pct->ratio [cov]
  (when (number? cov)
    (/ (double cov) 100.0)))

(defn- class-crap [scores]
  (config/summarize-scores scores))

(defn- counted-sites
  "Site total from the snapshot. Older files omit `:sites` and record
  only killed, survived, and uncovered."
  [form]
  (or (:sites form)
      (+ (or (:killed form) 0)
         (or (:survived form) 0)
         (or (:uncovered form) 0))))

(defn- overlay-op [op crap-fn mut-fn]
  (cond-> (assoc op :text (or (:text op) (:name op)))
    crap-fn (assoc :cc (:complexity crap-fn)
                   :crap (:crap crap-fn)
                   :coverage (pct->ratio (:coverage crap-fn))
                   :metrics-status "current")
    mut-fn (assoc :killed (or (:killed mut-fn) 0)
                  :survived (or (:survived mut-fn) 0)
                  :uncovered (or (:uncovered mut-fn) 0)
                  :sites (counted-sites mut-fn))
    (:mutation-status mut-fn) (assoc :mutation-status (:mutation-status mut-fn))
    (or (:private op) (:private crap-fn) (:private mut-fn)) (assoc :private true)))

(defn- ops-for-class [c crap-fns mut-fns]
  (let [by-name (into {} (map (juxt :name identity) crap-fns))
        by-line (into {} (keep (fn [f]
                                (when-let [line (:line f)] [line f]))
                              crap-fns))
        existing (:ops c)
        known (set (map :name existing))
        extra (remove known (distinct (concat (map :name crap-fns)
                                              (keys mut-fns))))
        ops (concat existing (map (fn [nm] {:name nm :text nm}) extra))]
    (mapv (fn [op]
            (let [nm (:name op)
                  ;; Overloads share a name; their source line tells them apart.
                  score (or (some-> (:source-ident op) :line by-line)
                            (get by-name nm))]
              (overlay-op op score (get mut-fns nm))))
          ops)))

(defn- metric-owner?
  "A metric entry with a `:class` belongs to that class only; entries
  without one keep the namespace-wide behaviour of the other languages."
  [c entry]
  (or (nil? (:class entry))
      (= (:class entry) (:name c))
      (and (:qualified-name entry)
           (= (:qualified-name entry) (:qualified-name c)))))

(defn overlay-class
  ([c crap-by-ns mutate-by-ns]
   (overlay-class c crap-by-ns mutate-by-ns nil))
  ([c crap-by-ns mutate-by-ns owned]
   (let [nss (if (nil? owned)
               [(or (:ns c) (class-namespace c))]
               owned)
         crap-fns (->> (mapcat #(or (get crap-by-ns %) []) nss)
                       (filter #(metric-owner? c %))
                       vec)
         mut-fns (mutate-by-fn (merge-mutate (map #(get mutate-by-ns %) nss)))
         scores (keep :crap crap-fns)
         coverages (keep :coverage crap-fns)
         statements (reduce + 0 (keep :statements crap-fns))
         covered (reduce + 0 (keep :covered crap-fns))
         ops (ops-for-class c crap-fns mut-fns)
         killed (apply + 0 (keep :killed (vals mut-fns)))
         survived (apply + 0 (keep :survived (vals mut-fns)))
         uncovered (apply + 0 (keep :uncovered (vals mut-fns)))
         sites (apply + 0 (map counted-sites (vals mut-fns)))]
     (cond-> c
       (seq scores) (assoc :crap (class-crap scores)
                           :cc (apply + (map :complexity crap-fns))
                           :metrics-status "current")
       (seq coverages) (assoc :coverage (pct->ratio
                                          (/ (reduce + coverages) (count coverages))))
       (pos? statements) (assoc :coverage (/ (double covered) statements))
       (some :mutation-status (vals mut-fns))
       (assoc :mutation-status
              (if (every? #(= "complete" (:mutation-status %)) (vals mut-fns))
                "complete" "partial"))
       (seq mut-fns) (assoc :killed killed :survived survived :uncovered uncovered
                            :sites sites)
       (seq ops) (assoc :ops ops)))))

(defn- snapshot-names [metrics]
  (distinct (concat (keys (:crap metrics)) (keys (:mutate metrics)))))

(defn- direct-go-type-children
  "Direct Go type classes under each package class. A type's ns is the
  package ns plus `.Type`; a subpackage uses `/`, so it is not a child."
  [classes]
  (let [go-classes (filter #(= :go (:lang %)) classes)]
    (into {}
          (keep (fn [c]
                  (when-let [parent (class-namespace c)]
                    (let [prefix (str (name (:id c)) ".")
                          kids (vec (for [d go-classes
                                          :when (not= (:id c) (:id d))
                                          :let [child (class-namespace d)
                                                child-id (name (:id d))]
                                          :when (and child
                                                     (:name d)
                                                     (= child (str parent "." (:name d)))
                                                     (str/starts-with? child-id prefix)
                                                     (not (str/includes?
                                                            (subs child-id (count prefix))
                                                            ".")))]
                                      d))]
                      (when (seq kids) [(:id c) kids])))))
          go-classes)))

(defn- prefixed-crap [crap child]
  (for [row (get crap (:ns child))]
    (assoc row :name (str (:name child) "." (:name row)))))

(defn- prefixed-mutate [mutate child]
  (when-let [snapshot (get mutate (:ns child))]
    {:forms (mapv (fn [form]
                    (let [n (or (:name form)
                                (:name (form-name (:id form))))]
                      (cond-> form
                        n (assoc :name (str (:name child) "." n)))))
                  (:forms snapshot))}))

(defn- with-child-metrics
  "A package card also shows its types' methods as `Type.Method`. The
  snapshot itself keeps one row per function under `import/path.Type`; the
  package view is assembled here so both cards join."
  [metrics c children]
  (let [parent (:ns c)]
    (if (and (= :go (:lang c)) parent (seq children))
      (let [crap-rows (vec (mapcat #(prefixed-crap (:crap metrics) %) children))
            mut-snaps (vec (keep #(prefixed-mutate (:mutate metrics) %) children))]
        (cond-> metrics
          (seq crap-rows)
          (update-in [:crap parent] (fnil into []) crap-rows)
          (seq mut-snaps)
          (update-in [:mutate parent]
                     (fn [snapshot]
                       {:forms (vec (concat (:forms snapshot)
                                            (mapcat :forms mut-snaps)))}))))
      metrics)))

(defn- paint-class [c metrics owners children]
  (let [metrics (with-child-metrics metrics c (get children (:id c)))]
    (overlay-class c (:crap metrics) (:mutate metrics)
                   (get owners (class-namespace c)))))

(defn- paint-packages [packages metrics owners children]
  (mapv (fn [p]
          (update p :classes
                  (fn [cs]
                    (mapv #(paint-class % metrics owners children) cs))))
        packages))

(defn- paint-diagram [d metrics owners children]
  (ir/normalize (update d :packages #(paint-packages % metrics owners children))))

(defn- doc-classes [doc]
  (cond
    (:hierarchical doc) (:classes doc)
    (:diagrams doc) (mapcat (fn [d] (mapcat :classes (:packages d))) (:diagrams doc))
    :else (mapcat :classes (:packages doc))))

(defn- apply-scores
  [doc metrics]
  (if (and (empty? (:crap metrics)) (empty? (:mutate metrics)))
    doc
    (let [classes (doc-classes doc)
          owners (owners-by-ns classes (:prefix doc) (snapshot-names metrics))
          children (direct-go-type-children classes)]
      (cond
        (:hierarchical doc)
        (update doc :classes #(mapv (fn [c] (paint-class c metrics owners children)) %))
        (:diagrams doc)
        (update doc :diagrams #(mapv (fn [d] (paint-diagram d metrics owners children)) %))
        (:packages doc)
        (paint-diagram doc metrics owners children)
        :else doc))))

(defn apply-metrics [document metrics]
  (if (not= :verified (:metrics-mode document))
    (apply-scores document metrics)
    (if (snapshot/matches? document metrics)
      (apply-scores (snapshot/map-classes document snapshot/mark-current) metrics)
      (snapshot/map-classes document snapshot/clear-class))))
