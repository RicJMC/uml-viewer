(ns uml-viewer.adapters.web-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.web :as web]))

(describe "web transport"
  (it "decodes a form body"
    (should= {"type" "press" "x" "10" "value" "a b"}
             (web/parse-form "type=press&x=10&value=a+b")))

  (it "writes a multi-line server-sent event"
    (should= "event: frame\ndata: one\ndata: two\n\n"
             (web/sse-message "frame" "one\ntwo")))

  (it "sends only changed view parts"
    (let [shown {:title "A" :frame "<svg/>" :card {:title "" :svg ""}}
          views {:title "B" :frame "<svg/>" :card {:title "" :svg ""}}]
      (should= 1 (count (web/changed-messages shown views)))
      (should (str/includes? (first (web/changed-messages shown views)) "event: title"))))

  (it "fixes a project's port inside the web range"
    (let [root (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir")))]
      (should= (web/preferred-port root) (web/preferred-port root))
      (should (<= 20000 (web/preferred-port root) 29999))))

  (it "serves the page and its health check"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-web-" (System/nanoTime)))
          path (io/file dir "diagram.edn")]
      (.mkdirs dir)
      (spit path (pr-str {:hierarchical true
                          :title "Web demo"
                          :src "."
                          :prefix "demo"
                          :classes [{:id :a :name "Alpha" :ns "demo.a"}]
                          :edges []}))
      (let [url (web/start! (.getPath path) nil
                            {:standalone? true :port 0 :host "127.0.0.1"
                             :open? false})]
        (try
          (should (str/includes? (slurp (str url "health")) "ok"))
          (should (str/includes? (slurp url) "<div id=\"stage\">"))
          (finally
            (web/stop!)))))))
