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
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.domain.policy :as policy]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.detail :as detail]
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
                    :crap {:mu 2} :coverage 1.0 :sites 3
                    :ops [{:name "run" :cc 2 :sites 3}]}]}
          result (overlay/apply-metrics document {:status "stale"})
          component (first (:classes result))]
      (should= "missing" (:metrics-status component))
      (should-be-nil (:coverage component))
      (should-be-nil (:sites component))
      (should-be-nil (:sites (first (:ops component))))
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
  (it "queues menu actions without waking a companion in standalone mode"
    (let [root (io/file "target" (str "python-mail-" (System/nanoTime)))
          previous @sketch/!bridge
          notifications (atom 0)
          target {:id :book.Book :ns "library.book.Book" :kind :class}]
      (try
        (swap! sketch/!bridge assoc :standalone? true)
        (with-redefs [sketch/notify-agent! (fn [] (swap! notifications inc) true)]
          (doseq [operation [:context :refresh-crap :refresh-mutate
                            :refresh-mutate-all :omit]]
            (let [result (sketch/request-agent! root operation {:target target})]
              (should= false (:woke? result)))))
        (doseq [operation [:context :refresh-crap :refresh-mutate
                          :refresh-mutate-all :omit]]
          (let [command (mailbox/take-command! (mailbox/to-agent root))]
            (should= operation (:op command))
            (should= target (:target command))))
        (should-be-nil (mailbox/take-command! (mailbox/to-agent root)))
        (should= 0 @notifications)
        (finally
          (reset! sketch/!bridge previous)
          (doseq [file (reverse (file-seq root))]
            (io/delete-file file true))))))

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
        (should= true (:standalone? @sketch/!bridge))
        ((:regenerate @sketch/!bridge))
        (should= :python (:lang (edn/read-string (slurp out))))
        (finally
          (reset! sketch/!bridge previous)
          (io/delete-file out true))))))

(describe "Python diagrams with upstream display controls"
  (it "shows completed Python mutation sites in the class card"
    (let [component {:id :book.Book :name "Book" :ns "library.book.Book"
                     :metrics-status "current" :ops [{:name "borrow"}]}
          mutants {"library.book.Book"
                   {:forms [{:name "borrow" :killed 1 :survived 1 :uncovered 0
                             :status "complete" :total 2}]}}
          painted (overlay/overlay-class component {} mutants)
          rows (detail/rows {:class painted})
          member (first (filter #(= "borrow" (:op-name %)) rows))]
      (should= 2 (:sites painted))
      (should-be-nil (:mut-note member))
      (should= "1" (:killed-s member))
      (should= "1" (:survived-s member))))

  (it "keeps partial measurements neutral inside a nested proposal group"
    (let [diagram {:hierarchical true
                   :classes [{:id :catalog.MemoryCatalog :name "MemoryCatalog"
                              :metrics-status "current" :mutation-status "partial"
                              :killed 1 :survived 0}]
                   :edges []
                   :proposals [{:id :design :layers
                                [{:id :library :nses
                                  [{:id :storage :label "Storage"
                                    :nses [:catalog.MemoryCatalog]}]}]}]}
          view (hierarchy/proposal-view diagram :design)
          group (first (filter #(= :storage (:id %))
                               (mapcat :classes (:packages view))))]
      (should= "partial" (:mutation-status group))
      (should-be-nil (draw/mutation-grade-of group))
      (should= [:catalog.MemoryCatalog] (mapv :id (:members group)))))

  (it "keeps Python dependencies visible as triangles and honors policy omissions"
    (let [graph (graph/scan python-graph/impl "examples/python/library" {:prefix "library"})
          options (generator/read-policy "examples/python.policy.edn")
          diagram (policy/apply-policy options graph)
          scene (document/compile-view diagram "target" [:catalog] {:declutter :triangles})
          dependencies (mapcat :deps (:dep-indicators scene))
          omitted (policy/apply-policy (assoc options :omit [:catalog]) graph)
          remaining (document/compile-view omitted "target" [])]
      (should (get-in scene [:diagram :hide-edges]))
      (should (some #(and (= :catalog.MemoryCatalog (:from %))
                         (= :catalog.Catalog (:to %))) dependencies))
      (should (some #(= :book (:id %)) (:classes remaining)))
      (should-not (some #(str/starts-with? (name (:id %)) "catalog")
                        (:classes remaining))))))
