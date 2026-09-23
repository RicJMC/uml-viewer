(ns uml-viewer.application.document
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.domain.hierarchy :as hierarchy]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.domain.log :as log]
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.policy :as policy]))

(defn compile-view
  "Compile the hierarchical view at `focus`, or the stacked document.
  `extra` is a boolean (legacy proposal?) or
  `{:proposal-id :declutter :proposal}`."
  ([doc] (compile-view doc (System/getProperty "user.dir") []))
  ([doc metrics-root] (compile-view doc metrics-root []))
  ([doc metrics-root focus] (compile-view doc metrics-root focus {}))
  ([doc metrics-root focus extra]
   (let [opts (if (map? extra) extra {:proposal (boolean extra)})
         painted (overlay/apply-metrics doc (overlay/load-metrics metrics-root))
         focus (or focus [])
         pid (or (:proposal-id opts)
                 (when (:proposal opts)
                   (:id (first (hierarchy/named-proposals painted)))))
         open-layer (:open-layer opts)
         declutter (or (:declutter opts) :full)
         declutter (if open-layer
                     (if (= declutter :classes) :elements declutter)
                     declutter)]
     (if (:hierarchical painted)
       (compose/compile-diagram
         (hierarchy/apply-declutter
           (cond
             (and pid open-layer (empty? focus))
             (hierarchy/layer-view painted pid open-layer)
             (and pid (empty? focus) (seq (hierarchy/named-proposals painted)))
             (hierarchy/proposal-view painted pid)
             :else
             (hierarchy/view-at painted focus))
           declutter))
       (compose/compile-document painted)))))

(defn compile-document
  ([doc] (compile-view doc (System/getProperty "user.dir") []))
  ([doc metrics-root] (compile-view doc metrics-root [])))

(def empty-scene
  {:classes []
   :packages []
   :edges []
   :sections []
   :size {:w 800 :h 600}
   :diagram {:title "UML"}})

(def waiting-message "Waiting for agent to create diagram.")

(defn- mail-seen-at [root]
  (mailbox/last-id (mailbox/to-viewer root)))

(defn waiting-state
  "Blank canvas until the companion sends :display. R still loads `path`."
  ([path] (waiting-state path (overlay/metrics-root path)))
  ([path root]
   {:path path
    :mtime 0
    :metrics-stamp nil
    :waiting true
    :scene empty-scene
    :selected nil
    :hover nil
    :detail-id nil
    :cam-x 0
    :cam-y 0
    :mail-seen (mail-seen-at root)}))

(defn- blank-state [path error]
  {:path path
   :mtime (.lastModified (java.io.File. path))
   :metrics-stamp (overlay/metrics-stamp (overlay/metrics-root path))
   :scene empty-scene
   :error error
   :selected nil
   :hover nil
   :detail-id nil
   :cam-x 0
   :cam-y 0
   :mail-seen (mail-seen-at (overlay/metrics-root path))})

(defn- paint
  "IR plus .metrics members. The class card reads this, not the scene."
  [doc path]
  (let [root (overlay/metrics-root path)]
    (overlay/apply-metrics doc (overlay/load-metrics root))))

(def session-keys
  [:focus :proposal-id :proposal :open-layer :declutter
   :cam-x :cam-y :zoom :selected :detail-id])

(defn save-session!
  "Write the current view (depth, pan, zoom, proposal) for --restart."
  [state]
  (when-let [path (:path state)]
    (mailbox/write-session! (overlay/metrics-root path)
                            (select-keys state session-keys)))
  state)

(defn- known-proposal-id [doc id]
  (when (and id (some #(= id (:id %)) (hierarchy/named-proposals doc)))
    id))

(defn load-path [path]
  (let [file (java.io.File. path)]
    (cond
      (not (.exists file))
      (blank-state path (str "file not found: " path))

      (not (.isFile file))
      (blank-state path (str "not a file: " path))

      :else
      (try
        (let [root (overlay/metrics-root path)
              doc (paint (ir/load-document path) path)]
          {:path path
           :mtime (.lastModified file)
           :metrics-stamp (overlay/metrics-stamp root)
           :doc doc
           :focus []
           :scene (compile-view doc root [] false)
           :selected nil
           :hover nil
           :detail-id nil
           :cam-x 0
           :cam-y 0
           :mail-seen (mail-seen-at root)})
        (catch Exception e
          (log/log-exception! e (str "load " path))
          (blank-state path (or (.getMessage e) (.getSimpleName (class e)))))))))

(defn- drop-missing-detail [state]
  (let [id (:detail-id state)]
    (cond-> state
      (and id (nil? (hit/class-by-id (:scene state) id)))
      (dissoc :detail-id))))

(defn restore-session
  "Reapply a saved view onto a freshly loaded state."
  [state snap]
  (if (or (not (map? snap)) (nil? (:doc state)))
    state
    (let [focus (vec (or (:focus snap) []))
          pid (known-proposal-id (:doc state) (:proposal-id snap))
          open (:open-layer snap)
          declutter (:declutter snap)
          root (overlay/metrics-root (:path state))
          scene (compile-view (:doc state) root focus
                              {:proposal-id pid
                               :declutter declutter
                               :open-layer open})]
      (-> state
          (assoc :focus focus
                 :proposal-id pid
                 :proposal (boolean pid)
                 :open-layer open
                 :declutter declutter
                 :scene scene
                 :cam-x (or (:cam-x snap) 0)
                 :cam-y (or (:cam-y snap) 0)
                 :zoom (or (:zoom snap) 1.0)
                 :selected (:selected snap)
                 :detail-id (:detail-id snap))
          drop-missing-detail))))

(defn restart-state
  "Load `path` and restore the last saved view, if any."
  [path]
  (restore-session (dissoc (load-path path) :waiting)
                   (mailbox/read-session (overlay/metrics-root path))))

(defn- resolve-display-path [state p]
  (let [f (io/file p)]
    (if (.isAbsolute f)
      (.getPath f)
      (.getPath (io/file (overlay/metrics-root (:path state)) p)))))

(defn apply-mail
  "Act on one unread command from the companion Grok."
  [state cmd]
  (let [state (assoc state :mail-seen (:id cmd))]
    (case (keyword (:op cmd))
      :display (if-let [p (:path cmd)]
                 (-> (load-path (resolve-display-path state p))
                     (assoc :mail-seen (:id cmd))
                     (dissoc :waiting))
                 state)
      :quit-for-restart (assoc state :quit-for-restart true)
      state)))

(defn poll-mail
  [state]
  (if-not (:path state)
    state
    (let [root (overlay/metrics-root (:path state))
          f (mailbox/to-viewer root)]
      (loop [state state]
        (if-let [cmd (mailbox/take-command! f (:mail-seen state))]
          (recur (apply-mail state cmd))
          state)))))

(defn maybe-reload [state]
  (if (or (:waiting state) (nil? (:path state)))
    state
    (let [file (java.io.File. (:path state))]
      (if-not (.exists file)
        state
        (let [path (:path state)
              root (overlay/metrics-root path)
              mtime (.lastModified file)
              stamp (overlay/metrics-stamp root)]
          (if (and (= mtime (:mtime state))
                   (= stamp (:metrics-stamp state)))
            state
            (try
              (let [doc (paint (ir/load-document path) path)
                    focus (or (:focus state) [])]
                (-> state
                    (dissoc :waiting)
                    (assoc :mtime mtime
                           :metrics-stamp stamp
                           :doc doc
                           :scene (compile-view doc root focus
                                                {:proposal-id (:proposal-id state)
                                                 :declutter (:declutter state)
                                                 :open-layer (:open-layer state)})
                           :error nil)
                    drop-missing-detail))
              (catch Exception e
                (log/log-exception! e (str "reload " path))
                (assoc state :mtime mtime :metrics-stamp stamp
                       :error (.getMessage e))))))))))

(defn- emit-doc [doc]
  (str ";; Generated by clj -M:ir from the policy file. Do not edit.\n"
       (binding [*print-namespace-maps* false
                 pprint/*print-right-margin* 90]
         (with-out-str (pprint/pprint doc)))))

(defn policy-path-for
  "Sibling `.policy.edn` of an IR path, if that file exists."
  [edn-path]
  (when (and edn-path (str/ends-with? (str edn-path) ".edn"))
    (let [p (str (subs (str edn-path) 0 (- (count (str edn-path)) 4))
                 ".policy.edn")]
      (when (.isFile (io/file p)) p))))

(defn- policy-path
  "Policy file for `doc`, resolved next to the IR when only a bare name is
   recorded in the :policy-file key."
  [edn-path doc]
  (let [named (:policy-file doc)
        parent (some-> (io/file edn-path) .getAbsoluteFile .getParentFile)]
    (cond
      (nil? edn-path) nil
      (and named (.isAbsolute (io/file named))) (str named)
      named (let [near (io/file parent named)]
              (if (.isFile near) (str near) (str named)))
      :else (policy-path-for edn-path))))

(defn- read-edn-file [path]
  (try
    (when (and path (.isFile (io/file path)))
      (edn/read-string (slurp path)))
    (catch Exception _ nil)))

(defn- spit-atomic!
  "Write `content` through a sibling temp file so readers never see a partial
   file (the viewer watches the IR; the companion may regenerate it)."
  [path content]
  (let [target (io/file path)
        tmp (io/file (str path ".tmp"))]
    (io/make-parents target)
    (spit tmp content)
    (try
      (java.nio.file.Files/move
        (.toPath tmp) (.toPath target)
        (into-array java.nio.file.CopyOption
                    [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                     java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception _
        (java.nio.file.Files/move
          (.toPath tmp) (.toPath target)
          (into-array java.nio.file.CopyOption
                      [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))

(defn- merge-proposals
  "Doc proposals win by id; proposals already on disk that this document has
   not seen are kept, so a viewer holding a stale document cannot erase them.
   `removed` holds the ids the user deleted explicitly."
  [on-disk doc removed]
  (let [current (remove #(contains? removed (:id %))
                        (policy/named-proposals on-disk))
        edited (vec (policy/named-proposals doc))
        by-id (into {} (map (juxt :id identity) edited))
        seen (set (map :id current))]
    (into (vec (keep (fn [p] (or (by-id (:id p)) p)) current))
          (remove #(seen (:id %)) edited))))

(defn write-proposals!
  "Persist named proposals to the IR and, when present, the policy file.
   Merges with the proposals already on disk; `opts` may carry `:removed`,
   the ids deleted on purpose. `:omit` and `:notice` survive the round-trip."
  ([edn-path doc] (write-proposals! edn-path doc {}))
  ([edn-path doc {:keys [removed] :or {removed #{}}}]
   (let [on-disk (read-edn-file edn-path)
         proposals (->> (merge-proposals (or on-disk doc) doc removed)
                        (mapv #(select-keys % [:id :name :layers :omit :notice])))
         doc (assoc doc :proposals proposals)
         written (assoc (or on-disk doc) :proposals proposals)
         p-path (policy-path edn-path doc)]
     (when edn-path
       (spit-atomic! edn-path (emit-doc written)))
     (let [p (read-edn-file p-path)]
       (when (map? p)
         (spit-atomic! p-path
                       (binding [*print-namespace-maps* false
                                 pprint/*print-right-margin* 90]
                         (with-out-str
                           (pprint/pprint (-> p (assoc :proposals proposals)
                                              (dissoc :proposal))))))))
     doc)))
