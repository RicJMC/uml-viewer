(ns uml-viewer.java-language.source-java
  "Java LanguageSource: open :file and find a method, constructor, or type."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  [(str "(?m)^[ \\t]*(?:@[\\w$.]+(?:\\([^)]*\\))?\\s+)*"
        "(?:(?:public|protected|private|static|final|abstract|synchronized"
        "|native|default|strictfp)\\s+)*"
        "(?!(?:return|new|throw|else|yield|assert)\\b)"
        "[\\w$<@][\\w$.<>\\[\\]?,&@ ]*\\s+%s\\s*\\(")
   "(?:class|interface|enum|record)\\s+%s\\b"])

(def impl (source/file-source pattern-fmts))

(source/register! :java impl)
