(ns uml-viewer.application.ir-generator-spec
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.clojure-language.graph-clojure]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.domain.policy :as policy]))

(defn- stub-scan [seen classes]
  (reify graph/LanguageGraph
    (scan [_ root opts]
      (reset! seen {:root root :opts opts})
      {:classes classes :edges []})))

(defn- edn-file [m]
  (doto (java.io.File/createTempFile "uml-ir" ".edn")
    (spit (pr-str m))))

(defn- demo-policy [m]
  (merge {:title "T"
          :packages [{:id :p :label "P" :nses [:a]}]
          :diagrams [{:title "P" :package :p}]}
         m))

(def demo-a {:id :a :name "A" :ns "demo.a"})
(def demo-b {:id :b :name "B" :ns "demo.b"})
(def demo-c {:id :c :name "C" :ns "demo.c"})

(describe "ir-generator emit"
  (it "writes a do-not-edit header and readable EDN"
    (let [s (ir-generator/emit {:title "T" :diagrams []})
          doc (edn/read-string s)]
      (should (str/starts-with? s ";; Generated"))
      (should= "T" (:title doc))
      (should= [] (:diagrams doc)))))

(describe "this project's policy"
  (it "builds a namespace tree and keeps Source as an interface"
    (let [policy (ir-generator/read-policy "examples/uml-viewer.policy.edn")
          graph (graph/scan-project (:src policy) {:prefix (:prefix policy)})
          extra (policy/unassigned policy graph)
          doc (policy/apply-policy policy graph)
          ids (set (map :id (remove :foreign (:classes doc))))
          source (first (filter #(= :source (:id %)) (:classes doc)))
          impl-edge (first (filter #(and (= :clojure-language.source-clojure (:from %))
                                         (= :source (:to %)))
                                   (:edges doc)))]
      (should (:hierarchical policy))
      (should= [] extra)
      (should (:hierarchical doc))
      (should (contains? ids :source))
      (should (contains? ids :clojure-language.source-clojure))
      (should (contains? ids :main.uml-viewer))
      (should= :interface (:stereotype source))
      (should= :implements (:kind impl-edge))
      (should (edn/read-string (ir-generator/emit doc))))))

(describe "ir-generator document"
  (it "scans with the given graph implementation"
    (let [impl (reify graph/LanguageGraph
                 (scan [_ root opts]
                   (should= "src" root)
                   (should= {:prefix "uml-viewer"} opts)
                   {:classes [{:id :a :name "A" :ns "demo.a"}]
                    :edges []}))
          policy {:title "T"
                  :packages [{:id :p :label "P" :nses [:a]}]
                  :diagrams [{:title "P" :package :p}]}
          doc (ir-generator/document impl policy)]
      (should= "T" (:title doc))
      (should= ["P"] (map :title (:diagrams doc)))))

  (it "uses policy src and prefix when present"
    (let [impl (reify graph/LanguageGraph
                 (scan [_ root opts]
                   (should= "trees" root)
                   (should= {:prefix "demo"} opts)
                   {:classes [demo-a]
                    :edges []}))
          doc (ir-generator/document impl (demo-policy {:src "trees"
                                                       :prefix "demo"}))]
      (should= "T" (:title doc)))))

(describe "ir-generator generate"
  (it "writes the document to the given path"
    (let [seen (atom nil)
          policy-f (edn-file (demo-policy {}))
          out-f (java.io.File/createTempFile "uml-out" ".edn")
          out (ir-generator/generate (stub-scan seen [demo-a])
                                     (.getPath policy-f)
                                     (.getPath out-f))
          body (slurp out-f)
          doc (edn/read-string body)]
      (should= (.getPath out-f) out)
      (should= "src" (:root @seen))
      (should= {:prefix "uml-viewer"} (:opts @seen))
      (should (str/starts-with? body ";; Generated"))
      (should= "T" (:title doc))
      (should= ["P"] (map :title (:diagrams doc)))))

  (it "uses policy :out when no path is given"
    (let [out-f (java.io.File/createTempFile "uml-out" ".edn")
          policy-f (edn-file (demo-policy {:out (.getPath out-f)}))
          out (ir-generator/generate (stub-scan (atom nil) [demo-a])
                                     (.getPath policy-f))]
      (should= (.getPath out-f) out)
      (should= "T" (:title (edn/read-string (slurp out-f))))))

  (it "prefers the given path over policy :out"
    (let [policy-out (java.io.File/createTempFile "uml-policy-out" ".edn")
          out-f (java.io.File/createTempFile "uml-out" ".edn")
          policy-f (edn-file (demo-policy {:out (.getPath policy-out)}))
          out (ir-generator/generate (stub-scan (atom nil) [demo-a])
                                     (.getPath policy-f)
                                     (.getPath out-f))]
      (should= (.getPath out-f) out)
      (should= "T" (:title (edn/read-string (slurp out-f))))))

  (it "scans with policy src and prefix"
    (let [seen (atom nil)
          out-f (java.io.File/createTempFile "uml-out" ".edn")
          policy-f (edn-file (demo-policy {:src "trees" :prefix "demo"}))]
      (ir-generator/generate (stub-scan seen [demo-a])
                             (.getPath policy-f)
                             (.getPath out-f))
      (should= "trees" (:root @seen))
      (should= {:prefix "demo"} (:opts @seen))))

  (it "defaults src, prefix, and out when the policy omits them"
    (let [seen (atom nil)
          written (atom nil)
          policy-f (edn-file (demo-policy {}))]
      (with-redefs [spit (fn [path s] (reset! written [path s]))]
        (let [out (ir-generator/generate (stub-scan seen [demo-a])
                                         (.getPath policy-f))]
          (should= "examples/uml-viewer.edn" out)
          (should= "examples/uml-viewer.edn" (first @written))
          (should (str/starts-with? (second @written) ";; Generated"))
          (should= "src" (:root @seen))
          (should= {:prefix "uml-viewer"} (:opts @seen))))))

  (it "prints unassigned namespaces to stderr"
    (let [out-f (java.io.File/createTempFile "uml-out" ".edn")
          policy-f (edn-file (demo-policy {}))
          err (java.io.StringWriter.)
          out (java.io.StringWriter.)]
      (binding [*err* err
                *out* out]
        (ir-generator/generate (stub-scan (atom nil) [demo-a demo-b demo-c])
                               (.getPath policy-f)
                               (.getPath out-f)))
      (should= "" (str out))
      (should= "Unassigned namespaces: demo.b, demo.c\n" (str err))))

  (it "does not warn when every namespace is assigned"
    (let [out-f (java.io.File/createTempFile "uml-out" ".edn")
          policy-f (edn-file (demo-policy {}))
          err (java.io.StringWriter.)
          out (java.io.StringWriter.)]
      (binding [*err* err
                *out* out]
        (ir-generator/generate (stub-scan (atom nil) [demo-a])
                               (.getPath policy-f)
                               (.getPath out-f)))
      (should= "" (str err))
      (should= "" (str out)))))

(describe "ir-generator report"
  (it "counts violations by rule and mutual imports"
    (let [doc {:title "T" :hierarchical true
               :levels [[:models] [:store :costs] [:api]]
               :classes [{:id :models :name "Models"}
                         {:id :store :name "Store"}
                         {:id :store.cache :name "Cache"}
                         {:id :costs :name "Costs"}
                         {:id :api :name "Api"}]
               :edges [{:from :models :to :api :kind :dependency :violating true}
                       {:from :api :to :models :kind :dependency :violating true}
                       {:from :store :to :costs :kind :dependency :violating true}
                       {:from :costs :to :store :kind :dependency :violating true}
                       {:from :store.cache :to :store :kind :dependency}]
               :forbidden-pairs [{:from [:api] :to [:models]}]
               :independent [[:store :costs]]}
          summary (ir-generator/summarize doc)]
      (should= 4 (:violations summary))
      (should= {:ranks 1 :forbidden 1 :independent 2} (:by-rule summary))
      (should= [{:from :models :to :api :rule :ranks}]
               (filter #(= :ranks (:rule %)) (:violating-edges summary)))
      (should-not (some #(= :store.cache (:from %))
                        (:violating-edges summary)))
      (should= [[:api :models] [:costs :store]] (:mutual-pairs summary))
      (should= ["Violations: 4 (ranks 1, forbidden 1, independent 2)"
                "  models -> api [ranks]"
                "  api -> models [forbidden]"
                "  store -> costs [independent]"
                "  costs -> store [independent]"
                "Mutual imports: 2"
                "  api <-> models"
                "  costs <-> store"]
               (ir-generator/report doc))))
  (it "reads the counts straight off a generated IR"
    (let [policy (ir-generator/read-policy "examples/uml-viewer.policy.edn")
          graph (graph/scan-project (:src policy) {:prefix (:prefix policy)})
          doc (policy/apply-policy policy graph)]
      (should (vector? (:mutual-pairs (ir-generator/summarize doc))))
      (should (seq (ir-generator/report doc))))))
