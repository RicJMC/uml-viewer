(ns uml-viewer.java-language.graph-java
  "Java LanguageGraph: one class per source file, imports, extends, implements."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(def ^:private skip-dir-names
  #{"target" "build" "node_modules"})

(defn- test-root?
  "`src/test`, `src/integrationTest`, `src/testFixtures`, and the like."
  [^java.io.File dir]
  (and (= "src" (some-> (.getParentFile dir) .getName))
       (boolean (re-find #"(?i)test" (.getName dir)))))

(defn- skip-dir? [^java.io.File dir]
  (let [name (.getName dir)]
    (or (str/starts-with? name ".")
        (contains? skip-dir-names name)
        (test-root? dir))))

(defn- excluded-name? [name]
  (or (contains? #{"package-info.java" "module-info.java"} name)
      (boolean (re-find #"^.+(?:Tests?|[a-z\d]IT)\.java$" name))))

(defn- source-files [root]
  (let [root (.getCanonicalFile (io/file root))]
    (->> (tree-seq (fn [f]
                     (and (.isDirectory f)
                          (or (= f root) (not (skip-dir? f)))))
                   (fn [dir] (vec (.listFiles dir)))
                   root)
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".java"))
         (remove #(excluded-name? (.getName %)))
         (sort-by #(.getPath %)))))

(defn- text-block? [^String source i]
  (.startsWith source "\"\"\"" (int i)))

(defn- text-block-end
  "Index just after the text block that opens at `i`."
  [^String source i n]
  (loop [j (+ i 3)]
    (cond
      (>= j n) n
      (= \\ (.charAt source j)) (recur (+ j 2))
      (text-block? source j) (+ j 3)
      :else (recur (inc j)))))

(defn- literal-end
  "Index just after the string, char, or text block at `i`, or nil."
  [source i c n]
  (cond
    (text-block? source i) (text-block-end source i n)
    (or (= c \") (= c \')) (graph/scan-quoted source i c n)
    :else nil))

(defn- blank-span
  "Spaces in place of `source` from `i` to `end`, keeping newlines."
  [^String source ^StringBuilder sb i end]
  (doseq [k (range i end)]
    (.append sb (if (= \newline (.charAt source k)) \newline \space)))
  end)

(defn- mask-step [^String source sb i n]
  (let [c (.charAt source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt) (graph/mask-line-comment source sb i n)
      (graph/block-comment? c nxt) (graph/mask-block-comment source sb i n)
      :else (if-let [end (literal-end source i c n)]
              (blank-span source sb i end)
              (do (.append ^StringBuilder sb c)
                  (inc i))))))

(defn- mask
  "Comments and the contents of literals become spaces. Newlines stay, so
  every brace, semicolon, and word left in the text is code."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (recur (mask-step source sb i n))))))

(defn- opener? [c]
  (or (= c \() (= c \[) (= c \{)))

(defn- closer? [c]
  (or (= c \)) (= c \]) (= c \})))

(defn- level-end
  "Index of the first char in `stops` at the nesting level of `i`, or of
  the closer that leaves that level, or the end of `text`."
  [^String text i stops]
  (let [n (count text)]
    (loop [j i depth 0]
      (if (>= j n)
        n
        (let [c (.charAt text j)]
          (cond
            (and (zero? depth) (or (stops c) (closer? c))) j
            (opener? c) (recur (inc j) (inc depth))
            (closer? c) (recur (inc j) (dec depth))
            :else (recur (inc j) depth)))))))

(defn- brace-step [c depth]
  (cond
    (= c \{) (inc depth)
    (= c \}) (dec depth)
    :else depth))

(defn- depth-at
  "Brace depth of `text` at `i`."
  [^String text i]
  (loop [j 0 depth 0]
    (if (>= j i)
      depth
      (recur (inc j) (brace-step (.charAt text j) depth)))))

(defn- squeeze [s]
  (str/replace s #"\s+" ""))

(def ^:private dotted
  "[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*")

(defn- package-of [text]
  (some-> (re-find (re-pattern (str "\\bpackage\\s+(" dotted ")\\s*;")) text)
          second
          squeeze))

(defn- imports-of [text]
  (mapv (fn [[_ static name star]]
          {:name (squeeze name)
           :static (boolean static)
           :star (boolean star)})
        (re-seq (re-pattern (str "\\bimport\\s+(static\\s+)?(" dotted ")(\\s*\\.\\s*\\*)?\\s*;"))
                text)))

(defn- strip-nested
  "`s` without anything between `open` and `close`, nesting included."
  [s open close]
  (let [sb (StringBuilder.)]
    (reduce (fn [depth c]
              (cond
                (= c open) (inc depth)
                (= c close) (max 0 (dec depth))
                :else (do (when (zero? depth) (.append sb c))
                          depth)))
            0 s)
    (str sb)))

(defn- supertype-header
  "Type header after the name, without type parameters, record
  components, or annotations."
  [header]
  (-> header
      (strip-nested \< \>)
      (strip-nested \( \))
      (str/replace #"@\s*[A-Za-z_$][\w$.]*" " ")))

(defn- clause-names [header word]
  (when-let [[_ names] (re-find (re-pattern (str "\\b" word "\\b([^{]*?)"
                                                 "(?=\\b(?:extends|implements|permits)\\b|$)"))
                                header)]
    (->> (str/split names #",")
         (map squeeze)
         (remove str/blank?)
         vec)))

(defn- drop-annotations
  "`header` without `@Name` and `@Name(...)`."
  [^String header]
  (let [m (re-matcher #"@\s*[A-Za-z_$][\w$.]*\s*" header)]
    (if (.find m)
      (let [n (count header)
            end (.end m)
            end (if (and (< end n) (= \( (.charAt header end)))
                  (min n (inc (level-end header (inc end) #{})))
                  end)]
        (recur (str (subs header 0 (.start m)) " " (subs header end))))
      header)))

(defn- method-of
  "`{:name :prefix}` when `header` declares a method or constructor.
  The prefix holds the modifiers and the return type."
  [header]
  (let [h (drop-annotations header)]
    (when-let [paren (str/index-of h "(")]
      (when-let [[_ prefix name] (re-find #"(?s)^(.*?)([A-Za-z_$][\w$]*)\s*$" (subs h 0 paren))]
        (when-not (re-find #"\brecord\s+$" prefix)
          {:name name :prefix prefix})))))

(defn- returns? [prefix]
  (boolean (re-find #"[\w$>\]]\s+$" prefix)))

(defn- public-member? [prefix kind]
  (if (contains? #{:interface :annotation} kind)
    (not (re-find #"\bprivate\b" prefix))
    (boolean (re-find #"\bpublic\b" prefix))))

(defn- member-next
  "Where the member whose header stops at `stop` ends."
  [^String text stop n]
  (case (.charAt text stop)
    \; (inc stop)
    \{ (min n (inc (level-end text (inc stop) #{})))
    \= (let [end (level-end text (inc stop) #{\;})]
         (if (and (< end n) (= \; (.charAt text end))) (inc end) end))))

(defn- member-headers
  "Header of each member of the body that opens at `open`: the text up to
  its `;`, `{`, or `=`."
  [^String text open]
  (let [n (count text)]
    (loop [i (inc open) acc []]
      (let [stop (level-end text i #{\; \{ \=})]
        (if (or (>= stop n) (closer? (.charAt text stop)))
          acc
          (recur (member-next text stop n) (conj acc (subs text i stop))))))))

(defn- body-start
  "Where members begin in the body that opens at `open`. An enum's
  constants come first and end at a `;`."
  [^String text open kind]
  (if (= :enum kind)
    (let [semi (level-end text (inc open) #{\;})]
      (when (and (< semi (count text)) (= \; (.charAt text semi)))
        semi))
    open))

(defn- ops-of [text open kind type-name]
  (if-let [start (body-start text open kind)]
    (->> (member-headers text start)
         (keep method-of)
         (filter #(returns? (:prefix %)))
         (filter #(public-member? (:prefix %) kind))
         (map :name)
         (remove #{type-name})
         distinct
         vec)
    []))

(def ^:private type-pattern
  #"(?<![\w$.])(@\s*interface|class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")

(defn- type-kind [word]
  (if (str/starts-with? word "@") :annotation (keyword word)))

(defn- decl-start [^String text i]
  (loop [j (dec i)]
    (cond
      (neg? j) 0
      (contains? #{\; \{ \}} (.charAt text j)) (inc j)
      :else (recur (dec j)))))

(defn- abstract-at? [text i]
  (boolean (re-find #"\babstract\b" (subs text (decl-start text i) i))))

(defn- top-level-decls [^String text]
  (let [m (re-matcher type-pattern text)]
    (loop [acc []]
      (if (.find m)
        (let [decl {:at (.start m) :end (.end m)
                    :kind (type-kind (.group m 1)) :name (.group m 2)}]
          (recur (if (zero? (depth-at text (:at decl)))
                   (conj acc decl)
                   acc)))
        acc))))

(defn- read-type [^String text {:keys [at end kind name]}]
  (let [open (level-end text end #{\{ \;})
        header (supertype-header (subs text end open))
        body? (and (< open (count text)) (= \{ (.charAt text open)))]
    {:name name
     :kind kind
     :abstract (abstract-at? text at)
     :extends (or (clause-names header "extends") [])
     :implements (or (clause-names header "implements") [])
     :ops (if body? (ops-of text open kind name) [])}))

(def ^:private header-statement
  #"\b(?:package|import)\s+[^;]*;")

(defn read-module
  "Surface of Java `source`: package, imports, top-level types, and the
  simple and dotted names the code mentions."
  [source]
  (let [text (mask source)
        body (str/replace text header-statement " ")]
    {:package (package-of text)
     :imports (imports-of text)
     :types (mapv #(read-type text %) (top-level-decls text))
     :names (set (re-seq #"[A-Za-z_$][\w$]*" body))
     :qualified (set (map squeeze (re-seq #"[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)+"
                                          body)))}))

(defn- last-seg [dotted-name]
  (peek (str/split dotted-name #"\.")))

(defn- parent-name [dotted-name]
  (if-let [i (str/last-index-of dotted-name ".")]
    (subs dotted-name 0 i)
    dotted-name))

(defn- longest-type
  "Project id of the longest leading part of `dotted-name` that is a type."
  [dotted-name by-fq]
  (let [segs (str/split dotted-name #"\.")]
    (some (fn [k] (get by-fq (str/join "." (take k segs))))
          (range (count segs) 0 -1))))

(defn- on-demand? [imp]
  (and (:star imp) (not (:static imp))))

(defn- scope
  "Simple name to project id. A single-type import shadows the package,
  which shadows an on-demand import. A foreign import maps to nil."
  [imports package by-fq by-package]
  (merge (into {} (mapcat #(get by-package (:name %)) (filter on-demand? imports)))
         (get by-package package)
         (into {} (map (fn [imp] [(last-seg (:name imp)) (longest-type (:name imp) by-fq)])
                       (remove :star imports)))))

(defn- resolve-type
  "Project id of a type named in a header: `Base`, `Outer.Inner`, or
  `com.acme.Base`."
  [type-name scope by-fq]
  (let [head (first (str/split type-name #"\."))]
    (if (contains? scope head)
      (get scope head)
      (longest-type type-name by-fq))))

(defn- project-import?
  "True when `imp` names a project type, or brings in a project package."
  [imp id by-package]
  (boolean (or id
               (and (on-demand? imp) (contains? by-package (:name imp))))))

(defn- foreign-name
  "Foreign id of an import that names no project type. A static import
  names a member, so its owner is the dependency."
  [{:keys [name static star]}]
  (keyword (if (and static (not star)) (parent-name name) name)))

(defn- primary-type
  "The type named after the file, or the first one."
  [types base]
  (or (first (filter #(= base (:name %)) types))
      (first types)))

(defn- stereotype-of [t]
  (cond
    (contains? #{:interface :annotation} (:kind t)) :interface
    (= :enum (:kind t)) :enumeration
    (:abstract t) :abstract
    :else nil))

(defn- op-maps [names]
  (mapv (fn [n] {:name n :text n}) names))

(defn- supertypes [types k scope by-fq]
  (->> types
       (mapcat k)
       (keep #(resolve-type % scope by-fq))
       distinct
       vec))

(defn- analyze [{:keys [surface base] :as meta} by-fq by-package]
  (let [{:keys [imports types names qualified]} surface
        package (or (:package surface) "")
        in-scope (scope imports package by-fq by-package)
        imported (map (juxt identity #(longest-type (:name %) by-fq)) imports)
        primary (primary-type types base)]
    (merge meta
           {:requires (->> (concat (keep second imported)
                                   (keep in-scope names)
                                   (keep #(longest-type % by-fq) qualified))
                           distinct
                           vec)
            :foreigns (->> imported
                           (remove (fn [[imp id]] (project-import? imp id by-package)))
                           (map (comp foreign-name first))
                           distinct
                           vec)
            :inherits (supertypes types :extends in-scope by-fq)
            :impls (supertypes types :implements in-scope by-fq)
            :ops (op-maps (:ops primary))
            :stereotype (when primary (stereotype-of primary))})))

(defn- file-meta [^java.io.File file prefix]
  (let [surface (read-module (slurp file))
        base (str/replace (.getName file) #"\.java$" "")
        ns-str (graph/ns-join (:package surface) base)
        id (graph/id-of ns-str prefix)]
    {:id id
     :name (graph/module-name id)
     :ns ns-str
     :lang :java
     :file (graph/relative-path file)
     :base base
     :surface surface}))

(defn- type-names [m]
  (distinct (cons (:base m) (map :name (:types (:surface m))))))

(defn- fq-index
  "Fully qualified name of each top-level type to the id of its file."
  [metas]
  (into {} (for [m metas
                 t (type-names m)]
             [(graph/ns-join (:package (:surface m)) t) (:id m)])))

(defn- package-index
  "Package to the simple names of its top-level types and their file ids."
  [metas]
  (reduce (fn [acc m]
            (update acc (or (:package (:surface m)) "")
                    merge (into {} (map (fn [t] [t (:id m)]) (type-names m)))))
          {} metas))

(defn- public-class [c]
  (cond-> (dissoc c :surface :base :requires :foreigns :inherits :impls)
    (empty? (:ops c)) (dissoc :ops)
    (nil? (:stereotype c)) (dissoc :stereotype)))

(defrecord JavaGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "")
          metas (mapv #(file-meta % prefix) (source-files root))
          by-fq (fq-index metas)
          by-package (package-index metas)
          parsed (mapv #(analyze % by-fq by-package) metas)
          project-ids (set (map :id parsed))
          foreigns (->> parsed
                        (mapcat :foreigns)
                        distinct
                        (remove project-ids)
                        (mapv graph/foreign-class))]
      {:classes (into (mapv public-class parsed) foreigns)
       :edges (->> (mapcat #(graph/member-edges % [[:inherits :inheritance]
                                                   [:impls :implements]
                                                   [:requires :dependency]
                                                   [:foreigns :dependency]])
                           parsed)
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->JavaGraph))

(graph/register! :java impl)
