(ns uml-viewer.adapters.viewer-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.viewer :as viewer]
            [uml-viewer.application.document :as document]))

(defn- temp-diagram []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-web-viewer-" (System/nanoTime)))
        path (io/file dir "diagram.edn")]
    (.mkdirs dir)
    (spit path
          (pr-str {:hierarchical true
                   :title "Demo"
                   :src "."
                   :prefix "demo"
                   :classes [{:id :a :name "Alpha" :ns "demo.a"
                              :ops [{:name "run" :text "run"}]}
                             {:id :b :name "Beta" :ns "demo.b"}]
                   :edges [{:from :a :to :b :kind :dependency}]}))
    (.getPath path)))

(describe "web viewer"
  (it "drops the inspector in a narrow window"
    (should= 1000 (:view-w (viewer/dims {:window {:w 1280 :h 720}})))
    (should-not (:compact? (viewer/dims {:window {:w 1280 :h 720}})))
    (should= 390 (:view-w (viewer/dims {:window {:w 390 :h 844}})))
    (should (:compact? (viewer/dims {:window {:w 390 :h 844}}))))

  (it "renders the frame and keeps the card separate"
    (let [state (assoc (document/load-path (temp-diagram))
                       :window {:w 1280 :h 720})
          views (viewer/views state)]
      (should (string? (:frame views)))
      (should (str/includes? (:frame views) "<svg"))
      (should (str/includes? (:frame views) ">A<"))
      (should (str/includes? (:frame views) "Inspector"))
      (should= "" (get-in views [:card :svg]))))

  (it "resizes to a compact page and fits with F"
    (let [state (assoc (document/load-path (temp-diagram))
                       :window {:w 1280 :h 720})
          {:keys [state]} (viewer/handle state {} {"type" "resize" "w" "390" "h" "844"})
          views (viewer/views state)
          {:keys [state]} (viewer/handle state {} {"type" "key" "key" "f" "ctrl" "0"})]
      (should= 390 (get-in state [:window :w]))
      (should-not (str/includes? (:frame views) "Inspector"))
      (should (<= 0.5 (:zoom state)))))

  (it "keeps a large phone diagram at a readable minimum zoom"
    (let [state (-> (document/load-path (temp-diagram))
                    (assoc :window {:w 390 :h 844})
                    (assoc-in [:scene :size] {:w 4000 :h 3000 :min-x 0 :min-y 0}))
          {:keys [state]} (viewer/handle state {} {"type" "resize" "w" "390" "h" "844"})]
      (should= 0.5 (:zoom state)))))
