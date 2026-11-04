(ns uml-viewer.csharp-language.graph-csharp
  "C# LanguageGraph: one class per type, using edges, method ops for overlay."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph])
  (:import [java.util.regex Pattern]))

(def ^:private skip-dir-names
  #{"bin" "obj" ".vs" "target" "dist" "build" "out" "coverage"
    "node_modules" "test" "tests" "spec" "specs" "__tests__"})

(def ^:private generated-suffixes
  [".Designer.cs" ".g.cs" ".generated.cs"])

(def ^:private test-suffixes
  ["Tests.cs" "Test.cs"])

(defn- test-tree-segment? [segment]
  (let [s (str/lower-case segment)]
    (or (str/ends-with? s ".tests")
        (str/ends-with? s ".integrationtests")
        (str/ends-with? s ".testutils"))))

(defn- skip-dir? [name]
  (or (str/starts-with? name ".")
      (contains? skip-dir-names name)
      (test-tree-segment? name)))

(defn- excluded-file? [name]
  (or (some #(str/ends-with? name %) generated-suffixes)
      (some #(str/ends-with? name %) test-suffixes)))

(defn- under-test-tree? [file]
  (boolean
    (some test-tree-segment?
          (map str/lower-case (.split (str/replace (.getPath file) "\\" "/") "/")))))

(defn- relative-to-root [file root]
  (let [path (-> (.getCanonicalPath (io/file file))
                 (str/replace #"\\" "/"))
        root-path (-> (.getCanonicalPath (io/file root))
                      (str/replace #"\\" "/"))
        prefix (str root-path "/")]
    (if (str/starts-with? path prefix)
      (subs path (count prefix))
      (graph/relative-path file))))

(defn- source-files [root]
  (let [root (.getCanonicalFile (io/file root))]
    (->> (file-seq root)
         (remove (fn [f]
                   (when (.isDirectory f)
                     (skip-dir? (.getName f)))))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".cs"))
         (remove #(excluded-file? (.getName %)))
         (remove under-test-tree?)
         (sort-by #(.getPath %)))))

(defn- mask-csharp-step [source sb holes i n]
  (let [c (.charAt source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt)
      {:i (graph/mask-line-comment source sb i n) :holes holes}

      (graph/block-comment? c nxt)
      {:i (graph/mask-block-comment source sb i n) :holes holes}

      (= c \")
      (graph/copy-span sb source holes i (graph/scan-quoted source i \" n))

      :else
      (do (.append sb c)
          {:i (inc i) :holes holes}))))

(defn- mask-comments [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 holes []]
      (if (>= i n)
        {:text (str sb) :holes holes}
        (let [step (mask-csharp-step source sb holes i n)]
          (recur (:i step) (:holes step)))))))

(defn- in-hole? [holes i]
  (boolean (some (fn [[a b]] (and (<= a i) (< i b))) holes)))

(defn- matches [text holes pattern]
  (let [m (.matcher (Pattern/compile pattern) text)]
    (loop [acc []]
      (if (.find m)
        (recur (if (in-hole? holes (.start m))
                 acc
                 (conj acc {:start (.start m)
                            :group (when (pos? (.groupCount m)) (.group m 1))})))
        acc))))

(defn- namespace-of [text holes]
  (or (some :group (matches text holes "namespace\\s+([\\w.]+)\\s*;"))
      (some :group (matches text holes "namespace\\s+([\\w.]+)\\s*\\{"))))

(defn- type-kind-at [text start]
  (second (re-find #"\b(class|struct|record|interface)\b"
                   (subs text start (min (+ start 120) (count text))))))

(defn- type-decls [text holes]
  (matches text holes
           "(?m)(?:public|internal|private|protected)?\\s*(?:partial\\s+)?(?:class|struct|record|interface)\\s+(\\w+)"))

(defn- brace-end [text open]
  (loop [i (inc open) depth 1]
    (when (< i (count text))
      (case (.charAt text i)
        \{ (recur (inc i) (inc depth))
        \} (if (= depth 1) i (recur (inc i) (dec depth)))
        (recur (inc i) depth)))))

(defn- header-end [text start]
  (loop [i start]
    (when (< i (count text))
      (let [c (.charAt text i)]
        (cond
          (= c \{) i
          (= c \;) (inc i)
          :else (recur (inc i)))))))

(defn- bases-of [text start]
  (when-let [colon (str/index-of text ":" start)]
    (let [end (or (header-end text start) (count text))]
      (when (< colon end)
        (let [raw (str/trim (subs text (inc colon) end))]
          (->> (str/split raw #",")
               (map str/trim)
               (remove str/blank?)
               vec))))))

(defn- method-name? [name type-name]
  (and name (not= name type-name)
       (not (#{"get" "set" "add" "remove"} name))))

(defn- collect-method-names [body pattern type-name]
  (let [^java.util.regex.Matcher m (.matcher (Pattern/compile pattern) body)]
    (loop [acc []]
      (if (.find m)
        (let [name (.group m 1)]
          (recur (if (method-name? name type-name) (conj acc name) acc)))
        acc))))

(defn- private-method? [body name]
  (boolean
    (re-find
      (Pattern/compile
        (str "(?m)^\\s*private\\s+(?:async\\s+)?(?:virtual|override|static|sealed|new|abstract|extern|partial|\\s)*"
             "[\\w<>,\\[\\].?\\s]+\\s+"
             (Pattern/quote name)
             "\\s*[\\(<]"))
      body)))

(defn- method-names [body type-name]
  (vec
    (remove #(private-method? body %)
            (distinct
              (concat
                (collect-method-names
                  body
                  "(?m)^\\s*(?:public|private|protected|internal)\\s+(?:async\\s+)?(?:virtual|override|static|sealed|new|abstract|extern|partial|\\s)*[\\w<>,\\[\\].?\\s]+\\s+(\\w+)\\s*[\\(<]"
                  type-name)
                (collect-method-names
                  body
                  "(?m)^\\s*[\\w.]+\\s+(\\w+)\\s*\\("
                  type-name)
                (collect-method-names
                  body
                  "(?m)^\\s*\\w+\\s+(?:[\\w.]+\\.)+(\\w+)\\s*\\("
                  type-name))))))

(defn- usings [text holes]
  (->> (matches text holes "(?m)^using\\s+([A-Z][\\w.]*)\\s*;")
       (map :group)
       distinct
       vec))

(defn- resolve-type [name by-ns]
  (or (get by-ns name)
      (some (fn [[q id]]
              (when (str/ends-with? q (str "." name)) id))
            by-ns)))

(defn- qualified-ns [ns-root type-name]
  (cond
    (str/blank? ns-root) type-name
    (str/ends-with? ns-root (str "." type-name)) ns-root
    :else (str ns-root "." type-name)))

(defn- analyze-types [text file relative prefix]
  (let [{:keys [text holes]} (mask-comments text)
        ns-root (namespace-of text holes)
        using-list (usings text holes)
        decls (type-decls text holes)]
    (mapv
      (fn [{:keys [start group]}]
        (let [type-name group
              qualified (qualified-ns ns-root type-name)
              id (graph/id-of qualified prefix)
              kind (type-kind-at text start)
              open (header-end text start)
              body (when open
                     (subs text open (inc (or (brace-end text open) open))))
              bases (or (bases-of text start) [])
              ops (mapv (fn [n] {:name n :text n}) (method-names (or body "") type-name))]
          {:id id
           :name type-name
           :ns qualified
           :file relative
           :lang :csharp
           :kind kind
           :bases bases
           :using-list using-list
           :ops ops
           :stereotype (when (= "interface" kind) :interface)}))
      decls)))

(defn- link-types [classes]
  (let [by-ns (into {} (map (fn [c] [(:ns c) (:id c)]) classes))
        by-simple (into {}
                        (mapcat (fn [c]
                                  [[(:name c) (:id c)]
                                   [(last (str/split (:ns c) #"\.")) (:id c)]])
                                classes))]
    (mapv
      (fn [c]
        (let [inherits (vec (keep #(resolve-type % by-ns) (:bases c)))
              deps (vec (distinct (keep #(resolve-type % by-ns) (:using-list c))))
              foreigns (vec (remove #(or (resolve-type % by-ns) (get by-simple %))
                                    (:using-list c)))]
          (assoc c
                 :inherits inherits
                 :requires inherits
                 :deps deps
                 :foreigns foreigns)))
      classes)))

(defn- as-edges [c]
  (concat
    (map (fn [to] {:from (:id c) :to to :kind :inheritance}) (:inherits c))
    (map (fn [to] {:from (:id c) :to to :kind :implements}) (:requires c))
    (map (fn [to] {:from (:id c) :to to :kind :dependency}) (:deps c))
    (map (fn [name] {:from (:id c) :to (keyword name) :kind :dependency}) (:foreigns c))))

(defn- merge-partial-entry [a b]
  (assoc a
         :ops (vec (distinct (concat (:ops a) (:ops b))))
         :using-list (vec (distinct (concat (:using-list a) (:using-list b))))
         :bases (vec (distinct (concat (:bases a) (:bases b))))))

(defn- coalesce-partials [classes]
  (->> classes
       (group-by :id)
       vals
       (map (fn [cs]
              (reduce merge-partial-entry (first cs) (rest cs))))
       (sort-by :ns)
       vec))

(defn- public-class [c]
  (cond-> (dissoc c :bases :using-list :deps :kind :requires :foreigns :inherits)
    (empty? (:ops c)) (dissoc :ops)
    (nil? (:stereotype c)) (dissoc :stereotype)))

(defrecord CSharpGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "app")
          rootf (.getCanonicalFile (io/file root))
          files (source-files rootf)
          classes (mapcat
                    (fn [file]
                      (analyze-types (slurp file)
                                       file
                                       (relative-to-root file rootf)
                                       prefix))
                    files)
          merged (coalesce-partials (vec classes))
          linked (link-types merged)
          project-ids (set (map :id linked))
          foreigns (->> linked
                          (mapcat :foreigns)
                          distinct
                          (remove project-ids)
                          (map graph/foreign-class))]
      {:classes (into (mapv public-class linked) foreigns)
       :edges (->> (mapcat as-edges linked)
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->CSharpGraph))

(graph/register! :csharp impl)
