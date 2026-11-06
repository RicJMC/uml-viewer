(ns uml-viewer.csharp-language.source-csharp-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.csharp-language.source-csharp :as cs]
            [uml-viewer.source :as source]))

(describe "C# LanguageSource"
  (it "finds a method line"
    (let [text "namespace Demo;\npublic class Board {\n  public int Run() => 1;\n}\n"]
      (should= 3 (source/start-line cs/impl text {:name "Run"}))
      (should (source/extract cs/impl text {:name "Run"})))))
