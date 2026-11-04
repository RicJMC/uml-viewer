(ns uml-viewer.main.cpp-metrics-spec
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.main.cpp-metrics :as cpp-metrics]))

(defn- temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "uml-cpp-metrics-" (System/nanoTime)))
    (.mkdirs)))

(defn- write-gcov! [root functions lines]
  (let [file (io/file root "demo.gcov.json")]
    (spit file (json/write-str
                 {:current_working_directory (.getPath root)
                  :files [{:file "demo.cpp"
                           :functions (mapv (fn [[name demangled start end count]]
                                              {:name name
                                               :demangled_name demangled
                                               :start_line start
                                               :end_line end
                                               :execution_count count})
                                            functions)
                           :lines (mapv (fn [[line name count]]
                                          {:line_number line
                                           :function_name name
                                           :count count})
                                        lines)}]}))
    file))

(describe "cpp metrics"
  (it "turns gcov functions into CRAP rows, grouped by class"
    (let [root (temp-dir)
          _ (spit (io/file root "demo.cpp")
                  (str "namespace app {\n"
                       "class Greeter {\n"
                       "public:\n"
                       "  int score(int x) {\n"
                       "    if (x > 0) { return x; }\n"
                       "    return 0;\n"
                       "  }\n"
                       "};\n"
                       "}\n"))
          gcov (write-gcov! root
                            [["_ZN3app7Greeter5scoreEi" "app::Greeter::score(int)" 4 7 1]]
                            [[4 "_ZN3app7Greeter5scoreEi" 1]
                             [5 "_ZN3app7Greeter5scoreEi" 1]
                             [6 "_ZN3app7Greeter5scoreEi" 0]
                             [7 "_ZN3app7Greeter5scoreEi" 0]])
          out (io/file root ".metrics/crap.edn")]
      (with-out-str
        (cpp-metrics/-main "--gcov" (.getPath gcov)
                           "--source-root" (.getPath root)
                           "--out" (.getPath out)))
      (let [entries (:entries (edn/read-string (slurp out)))
            entry (first entries)]
        (should= 1 (count entries))
        (should= "app" (:namespace entry))
        (should= "Greeter" (:class entry))
        (should= "score" (:name entry))
        (should= 4 (:line entry))
        (should= 2 (:complexity entry))
        (should= 50.0 (:coverage entry))
        (should= 4 (:statements entry))
        (should= 2 (:covered entry))
        (should= 2.5 (:crap entry)))))

  (it "keeps overloads apart by their source line"
    (let [root (temp-dir)
          _ (spit (io/file root "demo.cpp")
                  (str "namespace app {\n"
                       "class C {\n"
                       "public:\n"
                       "  int run(int x) { return x; }\n"
                       "  int run(double x) { return 1; }\n"
                       "};\n"
                       "}\n"))
          gcov (write-gcov! root
                            [["run_int" "app::C::run(int)" 4 4 1]
                             ["run_double" "app::C::run(double)" 5 5 0]]
                            [[4 "run_int" 1] [5 "run_double" 0]])
          out (io/file root ".metrics/crap.edn")]
      (with-out-str
        (cpp-metrics/-main "--gcov" (.getPath gcov)
                           "--source-root" (.getPath root)
                           "--out" (.getPath out)))
      (let [entries (:entries (edn/read-string (slurp out)))]
        (should= 2 (count entries))
        (should= #{4 5} (set (map :line entries)))
        (should= #{100.0 0.0} (set (map :coverage entries)))))))
