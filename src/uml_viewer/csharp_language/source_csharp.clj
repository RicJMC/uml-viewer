(ns uml-viewer.csharp-language.source-csharp
  "C# LanguageSource: open :file and find a method declaration."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["(?:public|private|protected|internal)\\s+(?:async\\s+)?(?:virtual|override|static|sealed|new|abstract|extern|partial|\\s)*[\\w<>,\\[\\].?\\s]+\\s+%s\\s*\\("
   "[\\w.]+\\s+%s\\s*\\("
   "(?:[\\w.]+\\.)+%s\\s*\\("
   "\\b%s\\s*\\("])

(def impl (source/file-source pattern-fmts))

(source/register! :csharp impl)
