(ns uml-viewer.java-language.graph-java-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.graph :as graph]
            [uml-viewer.java-language.graph-java :as java]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-java-" (System/nanoTime))))

(defn- type-named [surface type-name]
  (some #(when (= type-name (:name %)) %) (:types surface)))

(describe "java module surface"
  (it "reads the package, imports, and top-level supertypes"
    (let [surface (java/read-module
                    (str "package com . acme.model;\n"
                         "import java.util.List;\n"
                         "import static org.junit.Assert.assertEquals;\n"
                         "import com.acme.util.*;\n"
                         "public abstract class Book<T extends Comparable<T>>\n"
                         "    extends Base<T> implements Named, com.acme.io.Saved {\n"
                         "  static class Inner extends Hidden {}\n"
                         "}\n"
                         "interface Repo extends Store<Book>, Closeable {}\n"
                         "record Point(int x, int y) implements Shape {}\n"))]
      (should= "com.acme.model" (:package surface))
      (should= [{:name "java.util.List" :static false :star false}
                {:name "org.junit.Assert.assertEquals" :static true :star false}
                {:name "com.acme.util" :static false :star true}]
               (:imports surface))
      (should= ["Book" "Repo" "Point"] (map :name (:types surface)))
      (should= [:class :interface :record] (map :kind (:types surface)))
      (should (:abstract (type-named surface "Book")))
      (should-not (:abstract (type-named surface "Repo")))
      (should= ["Base"] (:extends (type-named surface "Book")))
      (should= ["Named" "com.acme.io.Saved"] (:implements (type-named surface "Book")))
      (should= ["Store" "Closeable"] (:extends (type-named surface "Repo")))
      (should= ["Shape"] (:implements (type-named surface "Point")))
      (should (contains? (:qualified surface) "com.acme.io.Saved"))))

  (it "keeps comments, strings, chars, and text blocks from hiding code"
    (let [surface (java/read-module
                    (str "/** class Fake extends Nope {} */\n"
                         "// import nope.Nope;\n"
                         "@Entity(name = \"x;{\")\n"
                         "public class Book {\n"
                         "  private static final String S = \"\"\"\n"
                         "      class Hidden { \\\"\"\" }\n"
                         "      \"\"\";\n"
                         "  private final char open = '{';\n"
                         "  private final char quote = '\\'';\n"
                         "  public String title() { return \"}\"; }\n"
                         "}\n"))]
      (should= [] (:imports surface))
      (should= ["Book"] (map :name (:types surface)))
      (should= ["title"] (:ops (first (:types surface))))
      (should-not (contains? (:names surface) "Nope"))
      (should-not (contains? (:names surface) "Hidden"))))

  (it "lists public methods, but not constructors, fields, or nested types"
    (let [surface (java/read-module
                    (str "public class Book {\n"
                         "  private Runnable r = () -> { run(); };\n"
                         "  public static final int[] SIZES = {1, 2};\n"
                         "  static { init(); }\n"
                         "  public Book(String isbn) {}\n"
                         "  Book() {}\n"
                         "  @Override\n"
                         "  @SuppressWarnings({\"a\", \"b\"})\n"
                         "  public String toString() { return null; }\n"
                         "  public <X> java.util.List<X> all(Class<X> k) throws Exception { return null; }\n"
                         "  public abstract int[] counts();\n"
                         "  public String toString(int width) { return null; }\n"
                         "  protected void guarded() {}\n"
                         "  void record(Event e) {}\n"
                         "  public static class Builder { public Book build() { return null; } }\n"
                         "  public record Pair(int a, int b) {}\n"
                         "}\n"))]
      (should= ["toString" "all" "counts"] (:ops (first (:types surface))))))

  (it "skips enum constants and treats interface methods as public"
    (let [surface (java/read-module
                    (str "enum Color {\n"
                         "  RED, GREEN(\"g\") { public void shade() {} }, BLUE;\n"
                         "  Color() {}\n"
                         "  public String code() { return null; }\n"
                         "}\n"
                         "enum Bare { ONE, TWO }\n"
                         "interface Repo {\n"
                         "  Book find(String isbn);\n"
                         "  default void touch() {}\n"
                         "  static Repo empty() { return null; }\n"
                         "  private void helper() {}\n"
                         "}\n"
                         "@interface Tag { String value() default \"\"; }\n"))]
      (should= ["code"] (:ops (type-named surface "Color")))
      (should= [] (:ops (type-named surface "Bare")))
      (should= ["find" "touch" "empty"] (:ops (type-named surface "Repo")))
      (should= :annotation (:kind (type-named surface "Tag")))
      (should= ["value"] (:ops (type-named surface "Tag"))))))

(describe "java graph"
  (it "scans a Maven tree by package, skips tests, and marks supertypes"
    (let [dir (temp-root)
          main "src/main/java/com/acme/"]
      (spit-file dir (str main "model/Animal.java")
                 (str "package com.acme.model;\n"
                      "public abstract class Animal {\n"
                      "  public abstract String speak();\n"
                      "}\n"))
      (spit-file dir (str main "model/Dog.java")
                 (str "package com.acme.model;\n"
                      "import com.acme.api.Named;\n"
                      "public class Dog extends Animal implements Named {\n"
                      "  public String speak() { return \"woof\"; }\n"
                      "  public String name() { return \"rex\"; }\n"
                      "  public static class Collar {}\n"
                      "}\n"))
      (spit-file dir (str main "api/Named.java")
                 (str "package com.acme.api;\n"
                      "public interface Named {\n"
                      "  String name();\n"
                      "}\n"))
      (spit-file dir (str main "api/Store.java")
                 (str "package com.acme.api;\n"
                      "public interface Store extends Named {}\n"))
      (spit-file dir (str main "api/Kind.java")
                 (str "package com.acme.api;\n"
                      "public enum Kind { DOG, CAT }\n"))
      (spit-file dir (str main "util/Strings.java")
                 (str "package com.acme.util;\n"
                      "public final class Strings {\n"
                      "  public static String join(Object o) { return \"\"; }\n"
                      "}\n"))
      (spit-file dir (str main "service/List.java")
                 (str "package com.acme.service;\n"
                      "public class List {}\n"))
      (spit-file dir (str main "service/Kennel.java")
                 (str "package com.acme.service;\n"
                      "import com.acme.model.*;\n"
                      "import java.util.List;\n"
                      "import static com.acme.util.Strings.join;\n"
                      "public class Kennel {\n"
                      "  private List<Dog> dogs;\n"
                      "  public String names() { return join(dogs); }\n"
                      "}\n"))
      (spit-file dir (str main "service/Audit.java")
                 (str "package com.acme.service;\n"
                      "import com.acme.model.Dog.Collar;\n"
                      "import org.slf4j.Logger;\n"
                      "import static org.junit.Assert.assertEquals;\n"
                      "import static org.mockito.Mockito.*;\n"
                      "public class Audit implements com.acme.api.Named {\n"
                      "  private com.acme.model.Animal last;\n"
                      "  private Collar collar;\n"
                      "  public String name() { return \"audit\"; }\n"
                      "}\n"))
      (spit-file dir (str main "package-info.java") "package com.acme;\n")
      (spit-file dir (str main "model/DogTest.java")
                 "package com.acme.model;\npublic class DogTest {}\n")
      (spit-file dir "src/test/java/com/acme/model/Helper.java"
                 "package com.acme.model;\npublic class Helper {}\n")
      (spit-file dir "src/integrationTest/java/com/acme/model/Probe.java"
                 "package com.acme.model;\npublic class Probe {}\n")
      (spit-file dir "target/generated-sources/com/acme/model/Gen.java"
                 "package com.acme.model;\npublic class Gen {}\n")
      (let [g (graph/scan (graph/lookup :java) dir {:prefix "com.acme"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            project (set (remove #(get-in by-id [% :foreign]) (keys by-id)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should= #{:model.Animal :model.Dog :api.Named :api.Store :api.Kind
                   :util.Strings :service.List :service.Kennel :service.Audit}
                 project)
        (should= "com.acme.model.Dog" (:ns (by-id :model.Dog)))
        (should= "Dog" (:name (by-id :model.Dog)))
        (should= :java (:lang (by-id :model.Dog)))
        (should (str/ends-with? (:file (by-id :model.Dog))
                                "src/main/java/com/acme/model/Dog.java"))
        (should= ["speak" "name"] (map :name (:ops (by-id :model.Dog))))
        (should= ["name"] (map :name (:ops (by-id :api.Named))))
        (should-not (contains? (by-id :api.Store) :ops))
        (should= :abstract (:stereotype (by-id :model.Animal)))
        (should= :interface (:stereotype (by-id :api.Named)))
        (should= :enumeration (:stereotype (by-id :api.Kind)))
        (should-be-nil (:stereotype (by-id :model.Dog)))
        (should (contains? edges [:model.Dog :model.Animal :inheritance]))
        (should (contains? edges [:model.Dog :model.Animal :dependency]))
        (should (contains? edges [:model.Dog :api.Named :implements]))
        (should (contains? edges [:model.Dog :api.Named :dependency]))
        (should (contains? edges [:api.Store :api.Named :inheritance]))
        (should (contains? edges [:service.Kennel :model.Dog :dependency]))
        (should (contains? edges [:service.Kennel :util.Strings :dependency]))
        (should (contains? edges [:service.Kennel :java.util.List :dependency]))
        (should (contains? edges [:service.Audit :model.Animal :dependency]))
        (should (contains? edges [:service.Audit :model.Dog :dependency]))
        (should (contains? edges [:service.Audit :org.slf4j.Logger :dependency]))
        (should (contains? edges [:service.Audit :org.junit.Assert :dependency]))
        (should (contains? edges [:service.Audit :org.mockito.Mockito :dependency]))
        (should (contains? edges [:service.Audit :api.Named :implements]))
        (should-not (contains? edges [:service.Kennel :service.List :dependency]))
        (should-not (contains? edges [:service.Kennel :model.Animal :dependency]))
        (should-not (contains? by-id :com.acme.model))
        (let [doc (ir-generator/document
                    (graph/lookup :java)
                    {:title "Acme"
                     :prefix "com.acme"
                     :lang :java
                     :src (str dir)
                     :hierarchical true
                     :foreign [:java.util]})
              ids (set (map :id (:classes doc)))
              doc-edges (set (map (juxt :from :to :kind) (:edges doc)))]
          (should= :java (:lang (some #(when (= :model.Dog (:id %)) %) (:classes doc))))
          (should (contains? ids :java.util))
          (should-not (contains? ids :java.util.List))
          (should-not (contains? ids :org.slf4j.Logger))
          (should (contains? doc-edges [:model.Dog :model.Animal :inheritance]))
          (should (contains? doc-edges [:model.Dog :api.Named :implements]))
          (should (contains? doc-edges [:service.Kennel :java.util :dependency]))
          (should-not (contains? doc-edges [:model.Dog :model.Animal :dependency]))))))

  (it "names a class in the default package by its file"
    (let [dir (temp-root)]
      (spit-file dir "Main.java"
                 (str "public class Main {\n"
                      "  public static void main(String[] args) { new Tool().run(); }\n"
                      "}\n"))
      (spit-file dir "Tool.java" "class Tool { public void run() {} }\n")
      (let [g (graph/scan (graph/lookup :java) dir {})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should= #{:Main :Tool} (set (keys by-id)))
        (should= "Main" (:ns (by-id :Main)))
        (should= ["main"] (map :name (:ops (by-id :Main))))
        (should (contains? edges [:Main :Tool :dependency])))))

  (it "takes ops from the type named after the file, or else the first type"
    (let [dir (temp-root)]
      (spit-file dir "Extra.java"
                 (str "interface Helper { void help(); }\n"
                      "public abstract class Extra { public void go() {} }\n"))
      (spit-file dir "Loose.java" "class Other { public void wander() {} }\n")
      (let [g (graph/scan (graph/lookup :java) dir {})
            by-id (into {} (map (juxt :id identity) (:classes g)))]
        (should= ["go"] (map :name (:ops (by-id :Extra))))
        (should= :abstract (:stereotype (by-id :Extra)))
        (should= ["wander"] (map :name (:ops (by-id :Loose))))))))
