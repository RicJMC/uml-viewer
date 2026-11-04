(ns uml-viewer.java-language.source-java-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.java-language.source-java]
            [uml-viewer.source :as source]))

(defn- java-file [label content]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-java-" label "-" (System/nanoTime)))
        file (io/file dir "Model.java")]
    (io/make-parents file)
    (spit file content)
    file))

(describe "java extractor"
  (it "finds a method declaration, not a call to it"
    (let [file (java-file "src"
                          (str "public class Model {\n"
                               "  public Animal load() {\n"
                               "    return walk(new Animal());\n"
                               "  }\n"
                               "\n"
                               "  public Animal walk(Animal animal) {\n"
                               "    return animal;\n"
                               "  }\n"
                               "}\n"))
          found (source/member-source {:lang :java
                                       :ns "com.acme.Model"
                                       :file (.getPath file)
                                       :name "walk"})]
      (should= :java (:lang found))
      (should (re-find #"Model\.java:6$" (:title found)))
      (should= 6 (:line found))
      (should (re-find #"public Animal walk\(Animal animal\)" (:body found)))))

  (it "finds a generic method and a constructor"
    (let [file (java-file "gen"
                          (str "public class Model {\n"
                               "  public Model(String name) {}\n"
                               "  public static <T extends Comparable<T>> T max(java.util.List<T> xs) {\n"
                               "    return null;\n"
                               "  }\n"
                               "}\n"))]
      (should= 3 (:line (source/member-source {:lang :java :file (.getPath file) :name "max"})))
      (should= 2 (:line (source/member-source {:lang :java :file (.getPath file) :name "Model"})))))

  (it "returns nil when the member is not in the file"
    (let [file (java-file "miss" "public class Model { public void walk() {} }\n")]
      (should-be-nil (source/member-source {:lang :java
                                            :file (.getPath file)
                                            :name "missing"}))))

  (it "opens a class at the top when no member is named"
    (let [file (java-file "mod" "public class Model { public void walk() {} }\n")
          found (source/member-source {:lang :java
                                       :file (.getPath file)
                                       :ns "com.acme.Model"})]
      (should= (.getPath file) (:file found))
      (should-be-nil (:line found)))))
