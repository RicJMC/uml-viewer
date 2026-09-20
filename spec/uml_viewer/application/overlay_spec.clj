(ns uml-viewer.application.overlay-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.application.overlay :as overlay]))

(describe "overlay"
  (it "paints class and op metrics from crap and mutate snapshots"
    (let [root (.getCanonicalPath (io/file "target" "overlay-demo"))
          crap-dir (io/file root ".metrics")
          mut-dir (io/file root ".metrics" "mutate" "uml_viewer")]
      (.mkdirs mut-dir)
      (spit (io/file crap-dir "crap.edn")
            (pr-str {:entries [{:name "go" :namespace "demo.app"
                                :complexity 3 :coverage 50.0 :crap 6.4}
                               {:name "hide" :namespace "demo.app"
                                :complexity 2 :coverage 100.0 :crap 2.0}]}))
      (spit (io/file mut-dir "demo.edn")
            (pr-str {:source "src/demo/app.clj"
                     :forms [{:id "defn/go" :hash "a" :killed 4 :survived 1 :uncovered 2 :sites 7}
                             {:id "defn-/hide" :hash "b" :killed 2 :survived 0 :uncovered 0 :sites 2}]}))
      (try
        (let [metrics (overlay/load-metrics root)
              d (ir/normalize {:packages
                               [{:id :p :label "P"
                                 :classes [{:id :demo :name "Demo"
                                            :ns "demo.app"
                                            :ops [{:name "go"}]}]}]
                               :edges []})
              painted (overlay/apply-metrics d metrics)
              c (get-in painted [:packages 0 :classes 0])
              go (first (filter #(= "go" (:name %)) (:ops c)))
              hide (first (filter #(= "hide" (:name %)) (:ops c)))]
          (should= 5 (:cc c))
          (should= 6 (:killed c))
          (should= 1 (:survived c))
          (should= 2 (:uncovered c))
          (should= 3 (:cc go))
          (should= 4 (:killed go))
          (should= 1 (:survived go))
          (should= 2 (:uncovered go))
          (should (:private hide))
          (should= 2 (:killed hide))
          (should= 0 (:uncovered hide))
          (should= 7 (:sites go))
          (should= 9 (:sites c)))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true))))))

  (it "matches snapshots by class :ns after normalize"
    (let [root (.getCanonicalPath (io/file "target" "overlay-ns"))
          crap-dir (io/file root ".metrics")]
      (.mkdirs crap-dir)
      (spit (io/file crap-dir "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (try
        (let [metrics (overlay/load-metrics root)
              d (ir/normalize {:packages
                               [{:id :p :label "P"
                                 :classes [{:id :board :name "Board"
                                            :ns "demo.board"}]}]
                               :edges []})
              painted (overlay/apply-metrics d metrics)
              c (get-in painted [:packages 0 :classes 0])
              place (first (filter #(= "place" (:name %)) (:ops c)))]
          (should= "demo.board" (:ns c))
          (should place)
          (should= 1 (:cc place)))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true))))))

  (it "finds .metrics by walking up from an EDN path"
    (let [root (io/file "target" "overlay-walk" "examples")
          metrics-dir (io/file "target" "overlay-walk" ".metrics")]
      (.mkdirs root)
      (.mkdirs metrics-dir)
      (spit (io/file root "diagram.edn") "{}")
      (try
        (should= (.getCanonicalPath (io/file "target" "overlay-walk"))
                 (overlay/metrics-root (io/file root "diagram.edn")))
        (finally
          (doseq [f (reverse (file-seq (io/file "target" "overlay-walk")))]
            (io/delete-file f true))))))

  (it "leaves a document alone when there is no snapshot"
    (let [d (ir/normalize {:packages [{:id :p :label "P"
                                       :classes [{:id :a :name "A"}]}]
                           :edges []})
          painted (overlay/apply-metrics d {:crap {} :mutate {}})]
      (should= d painted)))

  (it "stamps metrics files so a rewrite is visible"
    (let [root (.getCanonicalPath (io/file "target" (str "overlay-stamp-" (System/nanoTime))))
          crap-dir (io/file root ".metrics")]
      (.mkdirs crap-dir)
      (try
        (should= [] (overlay/metrics-stamp root))
        (spit (io/file crap-dir "crap.edn") (pr-str {:entries []}))
        (let [a (overlay/metrics-stamp root)]
          (should (seq a))
          (spit (io/file crap-dir "crap.edn")
                (pr-str {:entries [{:name "go" :namespace "demo.x"
                                    :complexity 2 :coverage 10.0 :crap 9.0}]}))
          (should-not= a (overlay/metrics-stamp root)))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true)))))))
