(ns uml-viewer.go-language.source-go
  "Go LanguageSource: find a func, method, or type in any file of the
  package. On a type class the method with that receiver wins. Without a
  receiver from `ns-name`, a dotted member name is a `Type.Method` pair."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.source :as source])
  (:import [java.util.regex Pattern]))

(defn- receiver
  "Type named after the last `.` of the import path's last segment, or nil."
  [ns-name]
  (let [seg (last (str/split (str ns-name) #"/"))]
    (when (str/includes? seg ".")
      (subs seg (inc (str/last-index-of seg "."))))))

(defn- find-at [text pattern from]
  (let [m (.matcher (Pattern/compile pattern) text)]
    (when (.find m from) (.start m))))

(defn- interface-spec [text recv q]
  (when-let [at (find-at text (format "(?m)^type\\s+%s(?:\\[[^\\]]*\\])?\\s+interface\\b" recv) 0)]
    (find-at text (format "(?m)^\\s+%s\\s*\\(" q) at)))

(defn- disambiguated
  "Split go-metrics' `init (file.go:12)` suffix from a member name."
  [member]
  (if-let [[_ name file line] (re-matches #"(.+)\s+\(([^)]*\.go):(\d+)\)$" member)]
    {:name name :file file :line (Long/parseLong line)}
    {:name member}))

(defn- member-parts
  "Quoted method name, quoted receiver pattern, and optional line from
  `ns-name` and `member-name`. The receiver from `ns-name` wins and a
  leading `Type.` is stripped; without one, a dotted member is
  `Type.Method`. `init (file.go:12)` keeps the line for the right init."
  [ns-name member-name]
  (let [{:keys [name file line]} (disambiguated (str member-name))
        recv (receiver ns-name)
        member (if (and recv (str/starts-with? name (str recv ".")))
                 (subs name (inc (count recv)))
                 name)
        dotted (str/includes? member ".")]
    [(if recv
       (Pattern/quote member)
       (if dotted
         (Pattern/quote (second (str/split member #"\." 2)))
         (Pattern/quote member)))
     (or (some-> recv Pattern/quote)
         (when dotted (Pattern/quote (first (str/split member #"\." 2)))))
     line
     file]))

(defn- find-at-line
  "Index of `pattern` in `text` on 1-based `line`, or nil."
  [text pattern line]
  (loop [from 0]
    (when-let [at (find-at text pattern from)]
      (if (= line (source/line-number text at))
        at
        (recur (inc at))))))

(defn member-start
  "Index of the declaration of `member-name` in `text`, or nil.
  A method on the receiver from `ns-name` wins over other declarations.
  `init (file.go:12)` selects the init on that line."
  [text ns-name member-name]
  (when (and text (seq (str member-name)))
    (let [[q recv line _file] (member-parts ns-name member-name)
          find-member (if line
                        #(find-at-line text % line)
                        #(find-at text % 0))]
      (or (when recv
            (or (find-member (format "(?m)^func\\s*\\([^)]*?\\b%s\\b[^)]*\\)\\s*%s\\s*\\(" recv q))
                (interface-spec text recv q)))
          (find-member (format "(?m)^func\\s+%s\\b" q))
          (find-member (format "(?m)^(?:type\\s+|[ \\t]+)%s\\b(?:\\[[^\\]]*\\])?\\s+(?:=\\s*)?[^=\\s]" q))
          (find-member (format "(?m)^func\\s*\\([^)]*\\)\\s*%s\\s*\\(" q))
          (find-member (format "(?m)^(?:var|const)\\s+%s\\b" q))))))

(defn- build-ignored? [source]
  (boolean (re-find #"(?m)^//\s*(?:go:build|\+build)\s+ignore\b" source)))

(defn- package-files [file]
  (let [f (io/file file)
        siblings (->> (.listFiles (.getParentFile (.getAbsoluteFile f)))
                      (filter #(str/ends-with? (.getName %) ".go"))
                      (sort-by #(.getName %)))]
    (->> (cons f (remove #(= (.getName %) (.getName f)) siblings))
         (remove #(or (str/ends-with? (.getName %) "_test.go")
                      (build-ignored? (slurp %)))))))

(defn- declaring-file [ident path]
  (let [{:keys [file]} (disambiguated (str (:name ident)))]
    (some (fn [f]
            (when (or (nil? file) (= file (.getName f)))
              (when (member-start (slurp f) (:ns ident) (:name ident))
                (str/replace (.getPath f) #"\\" "/"))))
          (package-files path))))

(def impl
  (reify source/LanguageSource
    (locate [_ ident]
      (when-let [path (source/existing-file ident)]
        (if (seq (str (:name ident)))
          (declaring-file ident path)
          path)))
    (extract [_ text ident]
      (when-let [start (member-start text (:ns ident) (:name ident))]
        (source/declaration-line text start)))
    (start-line [_ text ident]
      (when-let [start (member-start text (:ns ident) (:name ident))]
        (source/line-number text start)))
    (title [_ ident] (source/file-title ident))))

(source/register! :go impl)
