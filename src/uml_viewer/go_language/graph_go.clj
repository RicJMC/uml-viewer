(ns uml-viewer.go-language.graph-go
  "Go LanguageGraph: one class per package (directory); import, embedding,
  and interface-implementation edges. The module path comes from go.mod.
  A project import is a dependency. A standard-library import is ignored.
  Any other import is foreign, named by its repository
  (`github.com/jackc/pgx/v5/pgxpool` is `jackc.pgx`).

  A struct that embeds a type from another package inherits from it. An
  interface that embeds another interface inherits from it. A package that
  declares a type with every method of another package's interface
  implements that interface; single-method interfaces are ignored so that
  `Error` and `String` do not link unrelated packages."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(defn- canonical [file]
  (.getCanonicalFile (io/file file)))

(defn- go-mod-file
  "go.mod in `root` or the nearest directory above it, or nil."
  [root]
  (loop [dir (canonical root)]
    (when dir
      (let [f (io/file dir "go.mod")]
        (if (.isFile f) f (recur (.getParentFile dir)))))))

(defn module-path
  "Module path declared by go.mod `text`, or nil."
  [text]
  (second (re-find #"(?m)^\s*module\s+\"?([^\s\"]+)\"?" text)))

(defn- raw-string-end [source i n]
  (let [j (str/index-of source "`" (inc i))]
    (if j (inc j) n)))

(defn- mask-go-step [source sb i n]
  (let [c (.charAt ^String source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt) (graph/mask-line-comment source sb i n)
      (graph/block-comment? c nxt) (graph/mask-block-comment source sb i n)
      (= c \") (:i (graph/copy-span sb source [] i (graph/scan-quoted source i \" n)))
      (= c \') (:i (graph/copy-span sb source [] i (graph/scan-quoted source i \' n)))
      (= c \`) (:i (graph/copy-span sb source [] i (raw-string-end source i n)))
      :else (do (.append ^StringBuilder sb c) (inc i)))))

(defn mask-comments
  "Comments become spaces; string and rune literals are kept as they are."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (recur (mask-go-step source sb i n))))))

(defn- blank-literal
  "Append spaces for `source` between `i` and `end`, keeping newlines."
  [^StringBuilder sb ^String source i end]
  (dotimes [k (- end i)]
    (let [c (.charAt source (+ i k))]
      (.append sb (if (= c \newline) \newline \space)))))

(defn- mask-code-step [source sb i n]
  (let [c (.charAt ^String source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt) (graph/mask-line-comment source sb i n)
      (graph/block-comment? c nxt) (graph/mask-block-comment source sb i n)
      (= c \") (let [end (graph/scan-quoted source i \" n)]
                 (blank-literal sb source i end)
                 end)
      (= c \') (let [end (graph/scan-quoted source i \' n)]
                 (blank-literal sb source i end)
                 end)
      (= c \`) (let [end (raw-string-end source i n)]
                 (blank-literal sb source i end)
                 end)
      :else (do (.append ^StringBuilder sb c) (inc i)))))

(defn mask-code
  "Comments and the contents of string and rune literals become spaces;
  newlines survive. Used to read brace structure without a literal's
  braces confusing it."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (recur (mask-code-step source sb i n))))))

(def ^:private import-spec
  #"(?:([A-Za-z_][A-Za-z0-9_]*|\.)\s+)?(?:\"([^\"]*)\"|`([^`]*)`)")

(defn- import-specs
  "`{:name :path}` for each import in the header of masked `text`.
  `:name` is the alias, or nil for the package's own name."
  [text]
  (let [decls (re-matcher #"(?m)^(?:func|type|var|const)\b" text)
        header (subs text 0 (if (.find decls) (.start decls) (count text)))
        blocks (map second (re-seq #"(?m)^import\s*\(([^)]*)\)" header))
        singles (map second (re-seq #"(?m)^import\s+([^(\s][^\n]*)" header))]
    (->> (concat blocks singles)
         (mapcat #(re-seq import-spec %))
         (map (fn [[_ alias quoted raw]] {:name alias :path (or quoted raw)}))
         vec)))

(defn imports-of
  "Import paths in Go `source`, in order, without duplicates."
  [source]
  (->> (import-specs (mask-comments source))
       (map :path)
       distinct
       vec))

(defn- exported? [name]
  (Character/isUpperCase (.charAt ^String name 0)))

(defn receiver-type
  "Type name of a method receiver such as `(s *Service)` or `(l List[T])`."
  [recv]
  (some->> (-> recv
               (str/replace #"^\(\s*" "")
               (str/replace #"\s*\)$" "")
               (str/replace #"\[[^\]]*\]" "")
               (str/replace #"\*" " ")
               str/trim)
           (re-find #"([A-Za-z_][A-Za-z0-9_]*)$")
           second))

(defn- funcs
  "Functions and methods. A method is `Type.Method`, the name go-metrics
  writes, so the overlay can join it. A method is exported only when both
  the receiver type and the method are."
  [text]
  (for [[_ recv name] (re-seq #"(?m)^func\s*(\([^)]*\))?\s*([A-Za-z_][A-Za-z0-9_]*)\s*[\[(]" text)]
    (if recv
      (let [t (receiver-type recv)]
        {:name (str t "." name) :exported (and (exported? t) (exported? name))})
      {:name name :exported (exported? name)})))

(def ^:private type-spec
  #"(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^\]]*\])?\s+(?:=\s*)?(interface|struct|func|\S+)")

(defn- types [text]
  (concat
    (for [[_ name kind] (re-seq #"(?m)^type\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^\]]*\])?\s+(?:=\s*)?(interface|struct|func|\S+)" text)
          :when (not= name "(")]
      {:name name :kind kind})
    (for [[_ block] (re-seq #"(?s)\ntype\s*\((.*?)\n\)" (str "\n" text))
          [_ name kind] (re-seq type-spec block)]
      {:name name :kind kind})))

(defn read-file
  "Surface of one Go `source` file: package name, imports, exported ops, types."
  [source]
  (let [text (mask-comments source)
        fs (funcs text)]
    {:package (second (re-find #"(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_]*)" text))
     :imports (imports-of source)
     :ops (->> fs (filter :exported) (map :name) vec)
     :types (vec (filter #(exported? (:name %)) (types text)))}))

(defn close-of
  "Index just after the bracket that closes the one at `i`."
  [^String text i]
  (let [n (count text)]
    (loop [j i depth 0]
      (if (>= j n)
        n
        (let [c (.charAt text j)]
          (cond
            (#{\( \[ \{} c) (recur (inc j) (inc depth))
            (#{\) \] \}} c) (if (= 1 depth) (inc j) (recur (inc j) (dec depth)))
            :else (recur (inc j) depth)))))))

(defn- line-end
  "End of a one-line type expression at `i`: its newline, or a group's `)`."
  [^String text i]
  (let [n (count text)]
    (loop [j i depth 0]
      (if (>= j n)
        n
        (let [c (.charAt text j)]
          (cond
            (and (= c \newline) (zero? depth)) j
            (#{\( \[ \{} c) (recur (inc j) (inc depth))
            (#{\) \] \}} c) (if (zero? depth) j (recur (inc j) (dec depth)))
            :else (recur (inc j) depth)))))))

(defn- skip-blank [^String text i]
  (let [n (count text)]
    (loop [j i]
      (if (and (< j n)
               (let [c (.charAt text j)]
                 (or (Character/isWhitespace c) (= c \;))))
        (recur (inc j))
        j))))

(defn- ahead
  "Up to 256 characters of `text` from `i`, enough for a name or a keyword."
  [^String text i]
  (subs text i (min (count text) (+ i 256))))

(defn- body-type
  "`[:struct body end]`, `[:interface body end]`, or `[:other nil end]`."
  [^String text i]
  (if-let [[head kind] (re-find #"^(struct|interface)\s*\{" (ahead text i))]
    (let [brace (+ i (dec (count head)))
          end (close-of text brace)]
      [(keyword kind) (subs text (inc brace) (max (inc brace) (dec end))) end])
    [:other nil (line-end text i)]))

(defn- type-entry [^String text i]
  (when-let [type-name (re-find #"^[A-Za-z_][A-Za-z0-9_]*" (ahead text i))]
    (let [j (+ i (count type-name))
          j (if (and (< j (count text)) (= \[ (.charAt text j))) (close-of text j) j)
          j (+ j (count (re-find #"^[ \t]*=?[ \t]*" (ahead text j))))
          [kind body end] (body-type text j)]
      {:name type-name :pos i :kind kind :body body :end end})))

(defn- group-entries [^String text open]
  (let [end (dec (close-of text open))]
    (loop [k (skip-blank text (inc open)) entries []]
      (if-let [e (when (< k end) (type-entry text k))]
        (recur (skip-blank text (:end e)) (conj entries e))
        entries))))

(defn- type-decls [^String text]
  (let [m (re-matcher #"(?m)^type\b" text)]
    (loop [acc []]
      (if-not (.find m)
        acc
        (let [i (skip-blank text (.end m))]
          (recur (if (and (< i (count text)) (= \( (.charAt text i)))
                   (into acc (group-entries text i))
                   (if-let [e (type-entry text i)] (conj acc e) acc))))))))

(defn- matches-at [re text]
  (let [m (re-matcher re text)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc {:pos (.start m)
                          :groups (mapv #(.group m (int %))
                                        (range 1 (inc (.groupCount m))))}))
        acc))))

(defn read-decls
  "Imports with aliases, types with their bodies, functions, and methods
  of Go `source`. Comments and literal contents are masked first."
  [source]
  (let [text (mask-code source)]
    {:imports (import-specs (mask-comments source))
     :types (type-decls text)
     :funcs (mapv (fn [{:keys [pos groups]}]
                    {:name (first groups) :pos pos})
                  (matches-at #"(?m)^func\s+([A-Za-z_]\w*)" text))
     :methods (mapv (fn [{:keys [pos groups]}]
                      {:recv (first groups) :name (second groups) :pos pos})
                    (matches-at #"(?m)^func\s*\(\s*(?:[A-Za-z_]\w*\s+)?\*?\s*([A-Za-z_]\w*)(?:\[[^\]]*\])?\s*\)\s*([A-Za-z_]\w*)"
                                text))}))

(defn- top-level
  "`body` with everything inside nested braces blanked, newlines included."
  [body]
  (let [sb (StringBuilder.)]
    (reduce (fn [depth c]
              (let [inner (if (= c \}) (dec depth) depth)]
                (.append sb (if (pos? inner) \space c))
                (if (= c \{) (inc depth) inner)))
            0
            (str body))
    (str sb)))

(defn- body-lines [body]
  (->> (str/split (top-level body) #"[\n;]")
       (map str/trim)
       (remove str/blank?)))

(defn- embeds-of [{:keys [kind body]}]
  (case kind
    :struct (keep #(second (re-matches #"\*?([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)?)(?:\[[^\]]*\])?\s*(?:`\s*`|\"\s*\")?" %))
                  (body-lines body))
    :interface (keep #(re-matches #"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)?" %)
                     (body-lines body))
    nil))

(defn- interface-methods [{:keys [body]}]
  (set (keep #(second (re-find #"^([A-Za-z_]\w*)\s*\(" %)) (body-lines body))))

(defn- file-bindings [imports by-package]
  (into {}
        (keep (fn [{:keys [name path]}]
                (when-let [pkg (get by-package path)]
                  (let [bound (or name (:package pkg))]
                    (when-not (#{"_" "."} bound)
                      [bound (:id pkg)])))))
        imports))

(defn- resolve-ref [ref bindings own-id]
  (let [[qualifier type-name] (str/split ref #"\." 2)]
    (if type-name
      (when-let [id (get bindings qualifier)] [id type-name])
      [own-id qualifier])))

(defn- file-facts [source by-package own-id]
  (let [{:keys [imports] :as decls} (read-decls source)
        bindings (file-bindings imports by-package)]
    {:surface (read-file source)
     :types (mapv (fn [t]
                    (assoc t :refs (keep #(resolve-ref % bindings own-id)
                                         (embeds-of t))))
                  (:types decls))
     :methods (:methods decls)}))

(defn- interface-only? [surfaces]
  (let [types (mapcat :types surfaces)]
    (and (seq types)
         (empty? (mapcat :ops surfaces))
         (every? #(= "interface" (:kind %)) types))))

(def ^:private skipped-dirs #{"vendor" "testdata" "node_modules" "target" "tmp"})

(defn- skipped-dir? [^java.io.File d]
  (let [n (.getName d)]
    (or (contains? skipped-dirs n)
        (str/starts-with? n ".")
        (str/starts-with? n "_"))))

(defn- go-source? [^java.io.File f]
  (let [n (.getName f)]
    (and (.isFile f)
         (str/ends-with? n ".go")
         (not (str/ends-with? n "_test.go")))))

(defn package-dirs
  "Directories under `root` that hold non-test Go files, sorted."
  [root]
  (loop [queue [(canonical root)] acc []]
    (if (empty? queue)
      (vec (sort-by #(.getPath ^java.io.File %) acc))
      (let [dir (first queue)
            kids (vec (or (.listFiles ^java.io.File dir) []))
            subdirs (filter #(and (.isDirectory ^java.io.File %) (not (skipped-dir? %))) kids)]
        (recur (into (vec (rest queue)) subdirs)
               (cond-> acc (some go-source? kids) (conj dir)))))))

(defn- rel-path [root dir]
  (str/replace (str (.relativize (.toPath (canonical root)) (.toPath (canonical dir))))
               #"\\" "/"))

(defn- import-path [module mod-root dir]
  (let [rel (rel-path mod-root dir)]
    (if (str/blank? rel) module (str module "/" rel))))

(defn- dotted [path]
  (str/replace path "/" "."))

(defn stdlib?
  "True for a standard-library import: its first segment has no dot."
  [path]
  (not (str/includes? (first (str/split path #"/")) ".")))

(defn foreign-id
  "Repository-level id of an outside import: owner.repo for a hosted path,
  otherwise the host. Major-version segments (`v5`) are dropped."
  [path]
  (let [[host & more] (remove #(re-matches #"v\d+" %) (str/split path #"/"))
        tail (vec more)]
    (keyword (str/join "." (if (seq tail) (take 2 tail) [host])))))

(defn- dir-files [dir]
  (->> (.listFiles ^java.io.File dir)
       (filter go-source?)
       (sort-by #(.getName ^java.io.File %))
       vec))

(defn- package-class [{:keys [files sources path ns id package]} by-package]
  (let [facts (mapv #(file-facts %1 by-package id) sources)
        surfaces (mapv :surface facts)
        types (mapcat :types facts)
        methods (mapcat :methods facts)
        method-sets (reduce (fn [acc m]
                              (update acc (:recv m) (fnil conj #{}) (:name m)))
                            {}
                            methods)
        interfaces (into {}
                         (for [t types :when (= :interface (:kind t))]
                           [[id (:name t)]
                            {:methods (interface-methods t)
                             :embeds (vec (:refs t))}]))
        inherits (->> types
                      (mapcat :refs)
                      (map first)
                      (remove #(= id %))
                      distinct
                      vec)
        ops (vec (distinct (concat (map :name (mapcat :types surfaces))
                                   (mapcat :ops surfaces))))]
    (cond-> {:id id
             :ns ns
             :name (graph/module-name id)
             :lang :go
             :file (graph/relative-path (first files))
             :path path
             :package package
             :imports (vec (distinct (mapcat :imports surfaces)))
             :method-sets method-sets
             :interfaces interfaces
             :inherits inherits}
      (seq ops) (assoc :ops (mapv (fn [n] {:name n :text n}) ops))
      (interface-only? surfaces) (assoc :stereotype :interface))))

(defn- expand [interfaces k seen]
  (if (seen k)
    #{}
    (let [{:keys [methods embeds]} (get interfaces k)]
      (into (set methods)
            (mapcat #(expand interfaces % (conj seen k)) embeds)))))

(defn- implementations
  "Package ids whose type has every method of an interface in `contracts`."
  [c contracts]
  (->> (for [[_ owned] (:method-sets c)
             [owner wanted] contracts
             :when (and (not= owner (:id c)) (seq wanted) (every? owned wanted))]
         owner)
       distinct
       vec))

(defn- in-module? [module imp]
  (or (= imp module) (str/starts-with? imp (str module "/"))))

(defn- edges-of [classes module]
  (let [by-path (into {} (map (juxt :path :id) classes))]
    (concat
      (for [c classes
            imp (:imports c)
            :let [to (cond
                       (in-module? module imp) (get by-path imp)
                       (stdlib? imp) nil
                       :else (foreign-id imp))]
            :when (and to (not= to (:id c)))]
        {:from (:id c) :to to :kind :dependency :foreign (not (in-module? module imp))})
      (for [c classes, to (:impls c)]
        {:from (:id c) :to to :kind :implements})
      (for [c classes, to (:inherits c)
            :when (not= to (:id c))]
        {:from (:id c) :to to :kind :inheritance}))))

(defrecord GoGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [mod-file (or (go-mod-file root)
                       (throw (ex-info (str "no go.mod at or above " root) {:root (str root)})))
          module (or (module-path (slurp mod-file))
                     (throw (ex-info (str "no module line in " mod-file) {:file (str mod-file)})))
          mod-root (.getParentFile (canonical mod-file))
          prefix (or (:prefix opts) (dotted module))
          metas (->> (package-dirs root)
                     (keep (fn [d]
                             (let [fs (dir-files d)]
                               (when (seq fs)
                                 (let [sources (mapv slurp fs)
                                       package (second (re-find #"(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_]*)"
                                                                (first sources)))
                                       path (import-path module mod-root d)
                                       ns (dotted path)]
                                   {:dir d
                                    :files fs
                                    :sources sources
                                    :package package
                                    :path path
                                    :ns ns
                                    :id (graph/id-of ns prefix)})))))
                     vec)
          by-package (into {} (map (juxt :path #(select-keys % [:id :package])) metas))
          parsed (mapv #(package-class % by-package) metas)
          interfaces (apply merge {} (map :interfaces parsed))
          contracts (->> (keys interfaces)
                         (keep (fn [k]
                                 (let [wanted (expand interfaces k #{})]
                                   (when (>= (count wanted) 2)
                                     [(first k) wanted]))))
                         vec)
          parsed (mapv #(assoc % :impls (implementations % contracts)) parsed)
          edges (vec (distinct (edges-of parsed module)))
          foreigns (->> edges (filter :foreign) (map :to) distinct (mapv graph/foreign-class))]
      {:classes (into (mapv #(dissoc % :path :package :imports :method-sets
                                     :interfaces :inherits :impls)
                            parsed)
                      foreigns)
       :edges (mapv #(dissoc % :foreign) edges)})))

(def impl (->GoGraph))

(graph/register! :go impl)
