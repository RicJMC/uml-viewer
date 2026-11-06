(ns uml-viewer.adapters.web-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.web :as web])
  (:import [java.io ByteArrayInputStream]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

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

  (it "accepts posts only from this machine and its own origin"
    (should (web/same-origin-post? "127.0.0.1:20001" nil))
    (should (web/same-origin-post? "127.0.0.1:20001" "http://127.0.0.1:20001"))
    (should (web/same-origin-post? "LOCALHOST:20001" "http://localhost:20001"))
    (should (web/same-origin-post? "[::1]:20001" "http://[::1]:20001"))
    (should-not (web/same-origin-post? nil nil))
    ;; DNS rebinding: the Host name is not an address of this machine.
    (should-not (web/same-origin-post? "evil.example:20001" "http://evil.example:20001"))
    ;; Another origin on the same machine (a different local port).
    (should-not (web/same-origin-post? "127.0.0.1:20001" "http://127.0.0.1:9999"))
    ;; Sandboxed documents and foreign schemes.
    (should-not (web/same-origin-post? "127.0.0.1:20001" "null"))
    (should-not (web/same-origin-post? "127.0.0.1:20001" "https://127.0.0.1:20001")))

  (it "reads a body only up to its cap"
    (should= 100
             (count (web/read-body
                     (ByteArrayInputStream. (.getBytes (apply str (repeat 100 "a"))))
                     1024)))
    (should= ::web/too-large
             (web/read-body
              (ByteArrayInputStream. (.getBytes (apply str (repeat 4096 "a"))))
              1024)))

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
                             :open? false})
            client (HttpClient/newHttpClient)
            post (fn [origin body]
                   (let [b (HttpRequest/newBuilder (URI. (str url "event")))
                         _ (when origin (.header b "Origin" origin))
                         req (-> b
                                 (.POST (HttpRequest$BodyPublishers/ofString ^String body))
                                 .build)]
                     (.send client req (HttpResponse$BodyHandlers/ofString))))]
        (try
          (should (str/includes? (slurp (str url "health")) "ok"))
          (should (str/includes? (slurp url) "<div id=\"stage\">"))
          ;; A direct client (Host names this machine, no Origin) still works.
          (should= 200 (.statusCode (post nil "type=resize&w=640&h=480")))
          ;; Cross-origin pages are refused before the body is parsed. The
          ;; body name is an unknown event: if the guard ever slipped, the
          ;; reply would be 200 — a red spec, never a quit of this JVM.
          (should= 403 (.statusCode (post "http://evil.example" "type=cross-origin-attempt")))
          (should= 403 (.statusCode (post "null" "type=cross-origin-attempt")))
          (should= 403 (.statusCode (post "http://127.0.0.1:1" "type=cross-origin-attempt")))
          ;; An oversized body never reaches the parser.
          (should= 413 (.statusCode (post nil (apply str (repeat 20000 "a")))))
          (finally
            (web/stop!)))))))
