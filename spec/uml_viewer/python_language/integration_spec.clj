(ns uml-viewer.python-language.integration-spec
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.source :as source]
            [uml-viewer.python-language.graph-python :as python-graph]
            [uml-viewer.python-language.source-python :as python-source]
            [uml-viewer.application.ir-generator :as generator]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.hierarchy :as hierarchy]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.main.uml-viewer :as viewer]
            [uml-viewer.adapters.draw :as draw]
            [uml-viewer.adapters.source-window :as source-window]))

(describe "Python integration through the existing language interfaces"
  (it "scans classes and inheritance using the installed Python package"
    (let [result (graph/scan python-graph/impl "examples/python/library" {:prefix "library"})
          catalog (some #(when (= :catalog.MemoryCatalog (:id %)) %) (:classes result))]
      (should= "library.catalog.MemoryCatalog" (:ns catalog))
      (should= "find" (:name (last (:ops catalog))))
      (should (some #(and (= :catalog.MemoryCatalog (:from %))
                         (= :catalog.Catalog (:to %))
                         (= :inheritance (:kind %))) (:edges result)))
      (should= [] (:warnings result))))

  (it "opens the corresponding source through LanguageSource"
    (let [reader (python-source/create "examples/python/library" "library")
          found (source/member-source reader {:ns "library.book.Book" :name "borrow"})
          lines (str/split-lines (:body found))]
      (should= :python (:lang found))
      (should (str/includes? (:file found) "book.py"))
      (should (str/includes? (nth lines (dec (:line found))) "def borrow(self)"))))

  (it "generates a document with the source identity needed by the viewer"
    (let [out "target/python-integration.edn"]
      (try
        (generator/generate python-graph/impl "examples/python.policy.edn" out)
        (let [document (edn/read-string (slurp out))]
          (should= :python (:lang document))
          (should= :verified (:metrics-mode document))
          (should= "library" (:source-prefix document))
          (should (some #(= :book.Book (:id %)) (:classes document))))
        (finally (io/delete-file out true)))))

  (it "accepts standalone mode without changing existing argument defaults"
    (should= {:path "target/python.edn" :help? false :restart? false :standalone? true}
             (core/parse-args ["--standalone" "target/python.edn"])))

  (it "does not interpret Python punctuation as Clojure syntax"
    (let [html (source-window/source-lines->html "label = '<book>'; ready = True" 1 :python)]
      (should (str/includes? html "&lt;book&gt;"))
      (should-not (str/includes? html "class='cmt'"))
      (should (str/includes? html "class='hl'")))))

(describe "Verified quality snapshots"
  (it "carries weighted coverage and partial mutation state through the renderer"
    (let [component {:id :logic.Worker :name "Worker" :ns "sample.logic.Worker"
                     :metrics-status "current" :ops [{:name "run" :text "run(self)"}]}
          functions {"sample.logic.Worker"
                     [{:name "run" :complexity 2 :coverage 100 :crap 2
                       :covered 3 :statements 3}]}
          mutants {"sample.logic.Worker"
                   {:forms [{:name "run" :killed 1 :survived 0 :status "partial"}]}}
          painted (overlay/overlay-class component functions mutants)
          view (ir/normalize (hierarchy/view-at {:hierarchical true :classes [painted] :edges []}
                                                [:logic]))
          visible (first (mapcat :classes (:packages view)))]
      (should= 1.0 (:coverage visible))
      (should= "partial" (:mutation-status visible))
      (should= 2 (:cc (first (:ops visible))))
      (should-be-nil (draw/mutation-grade-of visible))))

  (it "removes stale scores and marks them unknown"
    (let [document {:metrics-mode :verified :source-root "sample"
                    :hierarchical true :classes [{:id :sample :name "Sample"
                    :crap {:mu 2} :coverage 1.0 :ops [{:name "run" :cc 2}]}]}
          result (overlay/apply-metrics document {:status "stale"})
          component (first (:classes result))]
      (should= "missing" (:metrics-status component))
      (should-be-nil (:coverage component))
      (should-be-nil (draw/crap-grade-of component))
      (should-be-nil (:cc (first (:ops component))))))

  (it "weights function coverage by executable statements"
    (let [functions {"sample" [{:name "small" :complexity 1 :coverage 100
                                :crap 1 :covered 1 :statements 1}
                               {:name "large" :complexity 1 :coverage 0
                                :crap 2 :covered 0 :statements 9}]}
          painted (overlay/overlay-class {:ns "sample" :ops []} functions {})]
      (should= 0.1 (:coverage painted)))))

(describe "Standalone Python startup"
  (it "selects the source adapter and regenerates without a companion"
    (let [out "target/python-standalone.edn"
          previous @sketch/!bridge
          started (atom nil)]
      (try
        (generator/generate python-graph/impl "examples/python.policy.edn" out)
        (with-redefs [sketch/start! (fn [& arguments] (reset! started arguments))]
          (with-out-str (viewer/-main "--standalone" out)))
        (should= out (first @started))
        (should= :python (:lang (second @started)))
        (should= true (last @started))
        (should= true (:keep-agent @sketch/!bridge))
        ((:regenerate @sketch/!bridge))
        (should= :python (:lang (edn/read-string (slurp out))))
        (finally
          (reset! sketch/!bridge previous)
          (io/delete-file out true))))))
