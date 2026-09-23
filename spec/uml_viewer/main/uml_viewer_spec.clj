(ns uml-viewer.main.uml-viewer-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.log :as log]
            [uml-viewer.main.uml-viewer :as main]))

(describe "viewer process"
  (it "treats help as a foreground request"
    (should (main/help-arg? ["--help"]))
    (should (main/help-arg? ["-h" "doc.edn"]))
    (should-not (main/help-arg? ["--restart" "doc.edn"])))

  (it "detaches from a terminal unless already the background JVM"
    (should (main/should-detach? [] nil :console))
    (should-not (main/should-detach? ["--help"] nil :console))
    (should-not (main/should-detach? [] "1" :console))
    (should-not (main/should-detach? [] nil nil)))

  (it "rebuilds a child JVM command that stays in the foreground"
    (let [cmd (vec (main/detach-command ["--restart" "doc.edn"]))
          i (.indexOf cmd "clojure.main")]
      (should= "clojure.main" (nth cmd i))
      (should= "uml-viewer.main.uml-viewer" (nth cmd (+ i 2)))
      (should= ["--restart" "doc.edn"] (vec (drop (+ i 3) cmd)))
      (should= log/log-name "uml-viewer-log.txt")
      (should= (main/setsid-bin) (first cmd))))

  (it "leaves the launcher session so the kernel cannot SIGHUP the viewer"
    (when-let [setsid (main/setsid-bin)]
      (should= setsid (first (main/detach-command []))))))
