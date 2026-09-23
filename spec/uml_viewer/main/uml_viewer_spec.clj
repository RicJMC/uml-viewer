(ns uml-viewer.main.uml-viewer-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.log :as log]
            [uml-viewer.main.uml-viewer :as main]))

(describe "viewer process"
  (it "logs exceptions to uml-viewer-log.txt in the working directory"
    (should= "uml-viewer-log.txt" log/log-name)
    (should main/-main)))
