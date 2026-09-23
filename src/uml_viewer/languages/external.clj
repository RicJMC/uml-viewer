(ns uml-viewer.languages.external
  "TypeScript scanner. Shells out to the tool's analyzer and registers
   LanguageGraph and LanguageSource for :typescript. Python lives in
   uml-viewer.python-language (bridge to the uml_viewer_python package)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]
            [uml-viewer.source :as source]))

(defn tool-home
  "Directory that contains analyzers/. UML_VIEWER_HOME wins; otherwise the
   working directory when it is this repo."
  []
  (let [env (System/getenv "UML_VIEWER_HOME")
        cwd (System/getProperty "user.dir")
        usable? (fn [p]
                  (and (seq p)
                       (not= p "__TOOL_HOME__")
                       (.isDirectory (io/file p "analyzers" "typescript"))))]
    (or (when (usable? env) env)
        (when (usable? cwd) cwd)
        (throw (ex-info "UML_VIEWER_HOME is not set and analyzers/ was not found"
                        {:uml-viewer-home env :cwd cwd})))))

(defn- absolute-path [path]
  (.getAbsolutePath (io/file (or path "."))))

(defn- run-json
  "Run `argv` and parse a JSON object from stdout."
  [argv]
  (let [pb (ProcessBuilder. (into-array String (map str argv)))
        _ (.directory pb (io/file (System/getProperty "user.dir")))
        _ (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
        proc (.start pb)
        out (slurp (.getInputStream proc))
        code (.waitFor proc)]
    (when-not (zero? code)
      (throw (ex-info (str "analyzer exited " code)
                      {:exit code :argv argv :out out})))
    (when (str/blank? out)
      (throw (ex-info "analyzer returned no JSON" {:argv argv})))
    (json/read-str out :key-fn keyword)))

(defn- node-argv [args]
  (into ["node" (str (io/file (tool-home) "analyzers" "typescript" "cli.mjs"))]
        args))

(defn- argv-for [lang args]
  (case (keyword lang)
    :typescript (node-argv args)
    (throw (ex-info (str "no external analyzer for " lang) {:lang lang}))))

(defn- id-keyword [value]
  (when (and value (not= value ""))
    (keyword (str/replace (str value) "/" "."))))

(defn- adapt-class [c]
  (let [id (id-keyword (:id c))]
    (cond-> {:id id
             :name (or (:name c) (when id (name id)))
             :ns (str (:ns c))}
      (:stereotype c) (assoc :stereotype (keyword (:stereotype c)))
      (:foreign c) (assoc :foreign true))))

(defn- adapt-edge [e]
  {:from (id-keyword (:from e))
   :to (id-keyword (:to e))
   :kind (keyword (:kind e))})

(defn- adapt-member [m]
  (cond-> {:ns (str (:ns m))
           :name (str (:name m))
           :complexity (long (or (:complexity m) 1))
           :line (long (or (:line m) 1))
           :end-line (long (or (:endLine m) (:line m) 1))
           :private (boolean (:private m))}
    (:file m) (assoc :file (str (:file m)))
    (some? (:coverage m)) (assoc :coverage (double (:coverage m)))))

(defn- adapt-graph [raw]
  {:classes (mapv adapt-class (:classes raw))
   :edges (mapv adapt-edge (:edges raw))
   :members (mapv adapt-member (:members raw))})

(defn- prefix-of [opts]
  (str (or (:prefix opts) "")))

(defrecord ExternalGraph [lang]
  graph/LanguageGraph
  (scan [_ root opts]
    (let [args ["scan" "--src" (absolute-path root) "--prefix" (prefix-of opts)]
          args (if (= lang :typescript)
                 (conj args "--project" (absolute-path root))
                 args)]
      (adapt-graph (run-json (argv-for lang args))))))

(defonce !locate-cache (atom {}))

(defn- locate-raw [lang ident]
  (let [src (absolute-path (or (:src ident) "src"))
        prefix (str (or (:prefix ident) ""))
        ns-name (str (:ns ident))
        member (str (:name ident))
        key [lang src prefix ns-name member]]
    (if (contains? @!locate-cache key)
      (get @!locate-cache key)
      (let [args ["locate" "--src" src "--prefix" prefix "--ns" ns-name]
            args (if (seq member) (conj args "--name" member) args)
            info (run-json (argv-for lang args))]
        (swap! !locate-cache assoc key info)
        info))))

(defrecord ExternalSource [lang]
  source/LanguageSource
  (locate [_ ident]
    (let [file (:file (locate-raw lang ident))]
      (when (and file (not= file "") (.exists (io/file file)))
        file)))
  (extract [_ _source ident]
    (when (and (seq (str (:name ident)))
               (number? (:line (locate-raw lang ident))))
      "found"))
  (start-line [_ _source ident]
    (let [line (:line (locate-raw lang ident))]
      (when (number? line) (long line))))
  (title [_ ident]
    (or (:title (locate-raw lang ident))
        (str (:ns ident) (when (:name ident) (str "/" (:name ident)))))))

(def typescript-graph (->ExternalGraph :typescript))
(def typescript-source (->ExternalSource :typescript))

(graph/register! :typescript typescript-graph)
(source/register! :typescript typescript-source)

(defn dispatch-source
  "LanguageSource that forwards to the extractor for `:lang` on the ident.
   Missing `:lang` stays on the Clojure extractor."
  []
  (reify source/LanguageSource
    (locate [_ ident]
      (when-let [impl (source/lookup (or (:lang ident) :clojure))]
        (source/locate impl ident)))
    (extract [_ text ident]
      (when-let [impl (source/lookup (or (:lang ident) :clojure))]
        (source/extract impl text ident)))
    (start-line [_ text ident]
      (when-let [impl (source/lookup (or (:lang ident) :clojure))]
        (source/start-line impl text ident)))
    (title [_ ident]
      (if-let [impl (source/lookup (or (:lang ident) :clojure))]
        (source/title impl ident)
        (str (:ns ident))))))

(defn- file? [root rel]
  (.isFile (io/file root rel)))

(def ^:private probe-skip
  #{"analyzers" ".git" "node_modules" ".venv" "venv" "target" "spec"
    "examples" ".uml-viewer" "scripts" ".metrics"})

(defn- ext-under? [dir ext]
  (boolean (and dir (.isDirectory dir)
                (some #(and (.isFile %) (str/ends-with? (.getName %) ext))
                      (file-seq dir)))))

(defn- policy-lang [root]
  (some (fn [rel]
          (let [f (io/file root rel)]
            (when (.isFile f)
              (let [lang (:lang (edn/read-string (slurp f)))]
                (when lang (keyword lang))))))
        ["uml-viewer.policy.edn" "examples/uml-viewer.policy.edn"]))

(defn probe-lang
  "Language to discover, ignoring an existing policy so a first policy can be written."
  [root]
  (let [root (io/file (or root "."))
        kids (->> (or (.listFiles root) [])
                  (filter #(.isDirectory %))
                  (remove #(probe-skip (.getName %))))]
    (cond
      (file? root "tsconfig.json") :typescript
      (ext-under? (io/file root "src") ".ts") :typescript
      (ext-under? (io/file root "src") ".tsx") :typescript
      (some #(ext-under? % ".ts") kids) :typescript
      :else nil)))

(defn detect-lang
  "Language of the project at `root`. An existing policy `:lang` wins."
  [root]
  (or (policy-lang root) (probe-lang root)))

(defn discover
  "Analyzer report for a fresh policy: lang, src, prefix, order, title."
  [root lang]
  (let [raw (run-json (argv-for lang ["discover" "--root" (absolute-path root)]))]
    {:lang (keyword (or (:lang raw) lang))
     :src (or (:src raw) "src")
     :prefix (or (:prefix raw) "")
     :order (mapv keyword (:order raw))
     :title (or (:title raw) "UML")}))

(defn coverage-entries
  "Member rows with coverage filled from `coverage-file`."
  [lang src prefix coverage-file]
  (let [args ["coverage"
                "--src" (absolute-path src)
                "--prefix" (str (or prefix ""))
                "--coverage" (absolute-path coverage-file)]
          args (if (= (keyword lang) :typescript)
                 (conj args "--project" (absolute-path src))
                 args)
          raw (run-json (argv-for lang args))]
    (mapv (fn [e]
            (cond-> {:namespace (str (:namespace e))
                     :name (str (:name e))
                     :complexity (long (or (:complexity e) 1))
                     :private (boolean (:private e))}
              (some? (:coverage e)) (assoc :coverage (double (:coverage e)))))
          (:entries raw))))

(defn mutation-snapshots
  "Per-namespace mutation snapshots from a mutmut, Stryker, or normalized report."
  [lang src prefix report]
  (let [args ["mutate"
                "--src" (absolute-path src)
                "--prefix" (str (or prefix ""))
                "--report" (absolute-path report)]
          args (if (= (keyword lang) :typescript)
                 (conj args "--project" (absolute-path src))
                 args)
          raw (run-json (argv-for lang args))]
    (mapv (fn [mod]
            {:namespace (str (:namespace mod))
             :source (str (or (:source mod) ""))
             :forms (mapv (fn [form]
                            {:name (str (:name form))
                             :private (boolean (:private form))
                             :killed (long (or (:killed form) 0))
                             :survived (long (or (:survived form) 0))
                             :uncovered (long (or (:uncovered form) 0))
                             :sites (long (or (:sites form) 0))})
                          (:forms mod))})
          (:modules raw))))

(defn agent-rules
  "Standing instructions for the companion of a Python or TypeScript project."
  [lang]
  (let [lang (keyword lang)
        coverage (case lang
                   :python (str "Run this project's tests under coverage "
                                "(poetry run coverage json -o coverage.json), then "
                                "uml coverage coverage.json")
                   :typescript (str "Run this project's tests under c8 or Istanbul so "
                                    "coverage-final.json exists, then "
                                    "uml coverage coverage-final.json"))
        mutate (case lang
                 :python (str "Run mutmut in this project, then "
                              "uml mutate .mutmut-cache "
                              "(or a normalized modules JSON, or a Stryker report)")
                 :typescript (str "Run Stryker, then "
                                  "uml mutate reports/mutation-report.json"))]
    (str "You are the UML-viewer companion for a " (name lang) " project. The current\n"
         "working directory is the project being examined.\n"
         "Show the diagram by running `uml` in the background. It scans, writes\n"
         "the EDN, and opens the window. It does not return while the window is\n"
         "open. Do not read the uml-viewer source to learn this.\n"
         "Then watch the mailbox with the monitor tool, after creating the file\n"
         "if it is missing:\n"
         "  tail -n 0 -F .uml-viewer/to-agent.edn\n"
         "Each new line is mail. Pop the head of :queue, handle it, and leave\n"
         "the monitor running. :regen means `uml refresh` only.\n"
         "Dots after the prefix are the tree. Do not invent\n"
         "Domain/Engine/Adapters packages. Do not edit the generated IR by hand.\n"
         "After every later source or policy change:\n"
         "1. Keep the policy as module nesting only. Do not re-home a module to\n"
         "   fake a layer/component. Layer and component mean the same thing.\n"
         "   Preserve :proposals (named components that are not namespaces). Do not\n"
         "   invent :proposals on launch. The inspector lists them; click the real\n"
         "   diagram above Proposals to return to the namespace tree. If instructed,\n"
         "   add a named proposal to :proposals in the policy (default name is a\n"
         "   timestamp) and regenerate the IR.\n"
         "2. Regenerate the IR with uml refresh so static complexity lands in\n"
         "   .metrics/crap.edn and the diagram reloads. " coverage ".\n"
         "3. Mutation is optional and slow. " mutate ".\n"
         "   Uncovered mutants are coverage gaps: keep the snapshot.\n"
         "4. Do not hand-edit the generated EDN.\n"
         "Mailbox: .uml-viewer/to-agent.edn and to-viewer.edn are queues\n"
         "{:next-id n :queue [cmd …]} (atomic: tmp then rename). Pop the head of\n"
         ":queue as you handle it (rewrite the file). Oldest first. Ops are\n"
         "{:id n :op :display :path \"...\"}, {:id n :op :regen},\n"
         "{:id n :op :quit-for-restart}, {:id n :op :context ...},\n"
         "and right-click element ops:\n"
         "{:id n :op :refresh-crap :target {...}}, :refresh-mutate,\n"
         ":refresh-mutate-all, :omit. :target is {:id :ns :kind :class|:component\n"
         " :proposal-id?}. For :refresh-crap run the coverage command above for\n"
         " that class or the files under that component, then uml refresh. For\n"
         " :refresh-mutate and :refresh-mutate-all run the mutation command on\n"
         " those files. For :omit, if :proposal-id is set add :id to that\n"
         " proposal's :omit; otherwise add it to policy :omit. Then regenerate.\n"
         ":context means the inspector selection is the discussion context:\n"
         "{:context :real} for the module tree, or {:context :proposal\n"
         " :proposal-id id :name \"...\"} for a named proposal. Treat that as\n"
         "the architecture under discussion until a later :context arrives.\n"
         "Pop to-agent.edn at the start of a turn as well as when the monitor\n"
         "fires. The window reloads the EDN when its mtime changes. Do not\n"
         "commit or push unless asked.\n")))

(defn write-agent-md!
  "Write the companion instructions where each agent looks.
   `.uml-viewer/AGENT.md` is for a Cursor agent. `.grok/rules/uml-viewer.md`
   is loaded automatically by the Grok CLI."
  [root lang]
  (let [text (agent-rules lang)
        agent-dir (io/file root ".uml-viewer")
        rules-dir (io/file root ".grok" "rules")]
    (.mkdirs agent-dir)
    (.mkdirs rules-dir)
    (spit (io/file agent-dir "AGENT.md") text)
    (spit (io/file rules-dir "uml-viewer.md") text)
    (.getPath (io/file agent-dir "AGENT.md"))))

(defn merge-policy
  "Discovered structure plus whatever design choices the existing policy already has.
   Does not invent :levels or :proposals."
  [existing discovered]
  (let [existing (or existing {})]
    (cond-> {:title (or (:title existing) (:title discovered) "UML")
             :src (:src discovered)
             :prefix (or (:prefix discovered) "")
             :lang (:lang discovered)
             :out (or (:out existing) "uml-viewer.edn")
             :hierarchical true
             :order (:order discovered)
             :foreign (or (:foreign existing) [])
             :edge-kinds (or (:edge-kinds existing) {})
             :omit (or (:omit existing) [])}
      (:levels existing) (assoc :levels (:levels existing))
      (:proposals existing) (assoc :proposals (:proposals existing))
      (:proposal existing) (assoc :proposal (:proposal existing))
      (:omit-edges existing) (assoc :omit-edges (:omit-edges existing)))))

(defn write-policy!
  [path policy]
  (spit path
        (binding [*print-namespace-maps* false
                  pprint/*print-right-margin* 90]
          (with-out-str (pprint/pprint policy))))
  path)

(defn read-policy-if [path]
  (let [f (io/file path)]
    (when (.isFile f)
      (edn/read-string (slurp f)))))
