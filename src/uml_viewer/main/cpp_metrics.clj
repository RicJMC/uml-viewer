(ns uml-viewer.main.cpp-metrics
  "Turn gcov JSON coverage into the viewer's `.metrics/crap.edn`.

  CRAP needs a cyclomatic complexity. Without a dedicated C++ analyser this
  tool estimates it from the function body (decision keywords, `&&`, `||`,
  and `?:`), which is useful for ranking but is not a certified CC count.

  Usage:
    clj -M:cpp-metrics --gcov target/coverage --source-root .
        [--out .metrics/crap.edn]

  `--gcov` is a `.gcov.json.gz` file, a `.json` file, or a directory holding
  either (the files `gcov --json-format` writes)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util.zip GZIPInputStream])
  (:gen-class))

(defn- canonical [path]
  (.getCanonicalFile (io/file path)))

(defn- read-json [^File file]
  (with-open [in (if (str/ends-with? (.getName file) ".gz")
                   (GZIPInputStream. (io/input-stream file))
                   (io/input-stream file))]
    (json/read (io/reader in) :key-fn keyword)))

(defn- gcov-files [path]
  (let [f (canonical path)]
    (cond
      (.isDirectory f)
      (->> (file-seq f)
           (filter #(and (.isFile %)
                         (or (str/ends-with? (.getName %) ".gcov.json.gz")
                             (str/ends-with? (.getName %) ".json"))))
           (sort-by #(.getPath %))
           vec)

      (.isFile f) [f]
      :else (throw (ex-info (str "gcov path not found: " path) {:path path})))))

(defn- source-under-root?
  [root file]
  (let [f (.getCanonicalFile (io/file file))]
    (str/starts-with? (.getPath f) (str (.getPath root) File/separator))))

(defn- strip-generics [s]
  (loop [s (str s)]
    (if-let [open (str/index-of s "<")]
      (let [close (str/last-index-of s ">")]
        (if (and close (> close open))
          (recur (str (subs s 0 open) (subs s (inc close))))
          s))
      s)))

(defn- function-parts
  "Namespace, class, and method of a demangled C++ function name."
  [demangled]
  (let [base (-> (str demangled)
                 (str/replace #"\(.*$" "")
                 str/trim)
        parts (->> (str/split base #"::")
                   (map (comp str/trim strip-generics))
                   (remove str/blank?))]
    (cond
      (>= (count parts) 3)
      {:namespace (str/join "." (butlast (butlast parts)))
       :class (nth parts (- (count parts) 2))
       :name (last parts)}

      (= 2 (count parts))
      {:namespace (first parts) :class nil :name (last parts)}

      :else {:namespace nil :class nil :name (first parts)})))

(defn- mask-code
  "Comments and literal contents become spaces; newlines survive."
  [source]
  (-> (str source)
      (str/replace #"\"(?:\\.|[^\"\\])*\"" "\"\"")
      (str/replace #"'(?:\\.|[^'\\])*'" "''")
      (str/replace #"(?m)//.*$" " ")
      (str/replace #"(?s)/\*.*?\*/" " ")))

(defn- complexity
  "Estimated cyclomatic complexity of `text`: 1 plus decision points."
  [text]
  (let [code (mask-code text)]
    (inc (+ (count (re-seq #"\b(?:if|for|while|case|catch)\b" code))
            (count (re-seq #"&&|\|\|" code))
            (count (re-seq #"\?" code))))))

(defn- function-lines [file-data fn]
  (let [name (:name fn)
        lines (filter #(= name (:function_name %)) (:lines file-data))]
    {:total (count lines)
     :covered (count (filter #(pos? (or (:count %) 0)) lines))}))

(defn- source-text [file start end]
  (let [f (io/file file)]
    (when (.isFile f)
      (let [lines (vec (str/split-lines (slurp f)))
            n (count lines)
            start (min n (max 1 (or start 1)))
            end (min n (max start (or end start)))]
        (if (pos? n)
          (str/join "\n" (subvec lines (dec start) end))
          "")))))

(defn- function-entry [root file-data fn]
  (let [{:keys [namespace class name]} (function-parts (:demangled_name fn))
        {:keys [total covered]} (function-lines file-data fn)
        start (:start_line fn)
        end (:end_line fn)
        coverage (cond
                   (pos? total) (/ (double covered) total)
                   (pos? (or (:execution_count fn) 0)) 1.0
                   :else 0.0)
        cc (complexity (or (source-text (:file file-data) start end) ""))]
    (when (and class (source-under-root? root (:file file-data)))
      {:namespace (or namespace "")
       :class class
       :name name
       :line start
       :complexity cc
       :coverage (* 100.0 coverage)
       :crap (+ cc (* cc cc (Math/pow (- 1.0 coverage) 3)))
       :statements total
       :covered covered})))

(defn- entries-of [root cwd data]
  (for [file-data (:files data)
        :let [file (:file file-data)
              file (if (and file (not (.isAbsolute (io/file file))))
                     (str cwd File/separator file)
                     file)
              file-data (assoc file-data :file file)]
        :when (and file (source-under-root? root file))
        fn (:functions file-data)
        :let [entry (function-entry root file-data fn)]
        :when entry]
    entry))

(defn- read-all [root paths]
  (->> paths
       (mapcat (fn [path]
                 (let [data (read-json path)
                       cwd (or (:current_working_directory data)
                               (some-> path .getParentFile .getPath))]
                   (entries-of root cwd data))))
       (reduce (fn [acc e]
                 (assoc acc [(:namespace e) (:class e) (:name e) (:line e)] e))
               {})
       vals
       (sort-by (juxt :namespace :class :line :name))
       vec))

(defn- usage []
  (println "Usage: clj -M:cpp-metrics --gcov PATH [--source-root DIR] [--out FILE]")
  (println)
  (println "  --gcov PATH        .gcov.json.gz, .json, or a directory of them.")
  (println "  --source-root DIR  Keep only files under DIR (default: user.dir).")
  (println "  --out FILE         Default: <source-root>/.metrics/crap.edn."))

(defn- parse-args [args]
  (let [args (vec args)]
    (cond
      (some #{"-h" "--help"} args) {:help? true}
      :else
      (loop [[a & more] args opts {}]
        (if (nil? a)
          opts
          (case a
            "--gcov" (recur (rest more) (assoc opts :gcov (first more)))
            "--source-root" (recur (rest more) (assoc opts :source-root (first more)))
            "--out" (recur (rest more) (assoc opts :out (first more)))
            (recur more opts)))))))

(defn -main [& args]
  (let [{:keys [help? gcov source-root out]} (parse-args args)]
    (if (or help? (nil? gcov))
      (usage)
      (let [root (canonical (or source-root (System/getProperty "user.dir")))
            out (io/file (or out (str (.getPath root) File/separator ".metrics/crap.edn")))
            entries (read-all root (gcov-files gcov))]
        (io/make-parents out)
        (spit out (str ";; Generated by clj -M:cpp-metrics from gcov JSON. Do not edit.\n"
                       (pr-str {:entries entries}) "\n"))
        (println (str "Wrote " (.getCanonicalPath out)
                      "; functions=" (count entries)
                      "; files=" (count (gcov-files gcov))))))))
