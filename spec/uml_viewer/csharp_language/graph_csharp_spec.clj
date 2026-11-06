(ns uml-viewer.csharp-language.graph-csharp-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.csharp-language.graph-csharp]
            [uml-viewer.graph :as graph]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(describe "C# LanguageGraph"
  (it "finds types, methods, and overlay namespaces"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-cs-" (System/nanoTime)))]
      (spit-file dir "App/Services/Board.cs"
                 (str "using System.Collections.Generic;\n\n"
                      "namespace Demo.App.Services;\n\n"
                      "public class Board : Panel\n"
                      "{\n"
                      "    public int Run(IList<int> xs) => xs.Count;\n"
                      "    private void Hide() {}\n"
                      "}\n"))
      (spit-file dir "App/Services/Panel.cs"
                 "namespace Demo.App.Services;\n\npublic class Panel { }\n")
      (let [impl (graph/lookup :csharp)
            {:keys [classes edges]} (graph/scan impl (str dir) {:prefix "Demo"})
            by-ns (into {} (map (fn [c] [(:ns c) c]) classes))
            board (get by-ns "Demo.App.Services.Board")
            panel (get by-ns "Demo.App.Services.Panel")]
        (should= "Demo.App.Services.Board" (:ns board))
        (should (some #(= "Run" (:name %)) (:ops board)))
        (should-not (some #(= "Hide" (:name %)) (:ops board)))
        (should panel)
        (should (some #(= (:id board) (:from %)) edges)))))

  (it "skips test and generated files"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-cs-skip-" (System/nanoTime)))]
      (spit-file dir "App/FooTests.cs"
                 "namespace Demo;\npublic class FooTests { public void T() {} }\n")
      (spit-file dir "App/Form.Designer.cs"
                 "namespace Demo;\npartial class Form { }\n")
      (spit-file dir "App/Real.cs"
                 "namespace Demo.App;\npublic class Real { public void Go() {} }\n")
      (let [impl (graph/lookup :csharp)
            {:keys [classes]} (graph/scan impl (str dir) {:prefix "Demo"})]
        (should= 1 (count (remove :foreign classes)))
        (should= "Demo.App.Real" (:ns (first (remove :foreign classes)))))))

  (it "skips *.Tests project folders and merges partial classes"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-cs-partial-" (System/nanoTime)))]
      (spit-file dir "src/App/Widget.cs"
                 "namespace Demo;\npublic partial class Widget {\n  public void A() {}\n}\n")
      (spit-file dir "src/App/Widget.extra.cs"
                 "namespace Demo;\npublic partial class Widget {\n  void IRunner.Run() {}\n}\n")
      (spit-file dir "test/Demo.Sync.Tests/GlpiTestHttp.cs"
                 "namespace Demo.Tests;\npublic class GlpiTestHttp { public void Help() {} }\n")
      (let [impl (graph/lookup :csharp)
            {:keys [classes]} (graph/scan impl (str dir) {:prefix "Demo"})
            widget (first (remove :foreign classes))]
        (should= 1 (count (remove :foreign classes)))
        (should= "Demo.Widget" (:ns widget))
        (should (some #(= "A" (:name %)) (:ops widget)))
        (should (some #(= "Run" (:name %)) (:ops widget)))))))
