(ns uml-viewer.main.cpp-adapter
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.cpp-language.clang-uml :as clang-uml]))

(def expected-class-ids #{:model.Node :store.Node :app.Controller})

(defn verify-fixture!
  "Assert the fixture contract against a freshly converted clang-uml diagram."
  ([doc] (verify-fixture! doc true))
  ([doc dependency?]
   (let [classes (into {} (map (juxt :id identity) (:classes doc)))
         ids (set (keys classes))
         edges (set (map (juxt :from :to :kind) (:edges doc)))
         controller (get classes :app.Controller)
         runs (filter #(= "run" (:name %)) (:ops controller))
         run-lines (set (map #(get-in % [:source-ident :line]) runs))
         has-dependency? (contains? edges
                                   [:app.Controller :store.Node :dependency])]
     (when-not (= expected-class-ids ids)
       (throw (ex-info "fixture class identities do not match"
                       {:expected expected-class-ids :actual ids})))
     (when-not (contains? edges [:app.Controller :model.Node :inheritance])
       (throw (ex-info "fixture inheritance relationship is missing" {:edges edges})))
     (when-not (= dependency? has-dependency?)
       (throw (ex-info (if dependency?
                         "fixture dependency relationship is missing"
                         "removed fixture dependency is still present")
                       {:edges edges})))
     (when-not (and (= 2 (count runs)) (= #{"int value" "double value"}
                                                 (set (map :signature runs)))
                    (= #{9 10} run-lines))
       (throw (ex-info "fixture overload identities or source locations changed"
                       {:runs (vec runs)})))
     (doseq [c (vals classes)]
       (when-not (get-in c [:source-ident :line])
         (throw (ex-info "fixture class source location is missing"
                         {:class (:id c)}))))
     true)))

(defn -main [& args]
  (let [[json-path edn-path source-root & flags] args
        opts (set flags)]
    (when-not (and json-path edn-path source-root)
      (throw (ex-info "usage: clj -M:cpp-ir <input.json> <output.edn> <source-root> [--verify-fixture] [--no-dependency]"
                      {:args args})))
    (let [json-file (io/file json-path)
          diagram (with-open [reader (io/reader json-file)]
                    (json/read reader :key-fn keyword))
          doc (clang-uml/convert diagram source-root)]
      (doseq [{:keys [severity code entity message]} (:diagnostics doc)]
        (binding [*out* *err*]
          (println (str (name severity) " [" (name code) "] " entity ": " message))))
      (when (or (opts "--verify-fixture") (opts "--no-dependency"))
        (verify-fixture! doc (not (opts "--no-dependency"))))
      (let [output (io/file edn-path)]
        (when-let [parent (.getParentFile output)]
          (.mkdirs parent))
        (spit output (str (pr-str doc) "\n")))
      (println (str "Wrote " (.getCanonicalPath (io/file edn-path))
                    "; classes=" (count (:classes doc))
                    "; relationships=" (count (:edges doc))
                    "; diagnostics=" (count (:diagnostics doc)))))))
