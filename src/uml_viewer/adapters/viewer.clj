(ns uml-viewer.adapters.viewer
  "Browser input applied to the viewer state. Each handler returns
  `{:state s :reply r}`; the reply tells the page to open a menu, a card,
  or a source view."
  (:require [clojure.string :as str]
            [uml-viewer.adapters.markup :as markup]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.adapters.svg :as svg]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.events :as events]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.policy :as policy]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.source :as source]))

(def default-window {:w 1500 :h 920})
(def compact-width 720)
(def compact-min-zoom 0.35)

(defn window [state]
  (or (:window state) default-window))

(defn dims
  "Window and view sizes. A narrow window drops the inspector so the
  diagram keeps the whole screen; the page's Back and Fit buttons cover
  the navigation that the sidebar carries on the desktop."
  [state]
  (let [{:keys [w h]} (window state)
        compact? (< w compact-width)
        view-w (if compact? w (max 0 (- w layout/sidebar-w)))]
    {:w w :h h
     :window-w w
     :window-h h
     :view-w view-w
     :compact? compact?}))

(defn initial-state
  "The first state: the last view on restart, else waiting for :display."
  [path restart?]
  (assoc (if restart?
           (document/restart-state path)
           (document/waiting-state path))
         :window default-window))

(defn- done
  ([state] {:state state :reply nil})
  ([state reply] {:state state :reply reply}))

(defn- compact-fit
  "`events/fit`, but do not shrink a phone diagram below a readable
  scale. The explicit Fit button still shows the whole scene."
  [state dims]
  (let [fitted (events/fit state dims)
        z (events/zoom-of fitted)]
    (if (and (:compact? dims) (< z compact-min-zoom))
      (let [{:keys [min-x min-y w h]} (get-in state [:scene :size])
            cz compact-min-zoom
            cx (+ (or min-x 0) (/ (double (or w 800)) 2.0))
            cy (+ (or min-y 0) (/ (double (or h 600)) 2.0))]
        (assoc fitted
               :zoom cz
               :cam-x (- cx (/ (double (:view-w dims)) 2.0 cz))
               :cam-y (- cy (/ (double (:window-h dims)) 2.0 cz))))
      fitted)))

(defn- keep-fit
  "Fit the scene while the automatic fit still owns the camera, and
  refit when the window or diagram changes size. Manual pan/zoom turns
  autofit off in `events`."
  [state]
  (let [{:keys [view-w window-h] :as dims} (dims state)
        size [view-w window-h]
        refit? (or (:fit? state)
                   (and (:autofit? state)
                        (not= size (:view-size state))))]
    (cond-> (assoc state :view-size size)
      refit? (compact-fit dims))))

(defn tick
  "Reload the diagram if its files changed and read the companion's mail."
  [state]
  (let [state (-> state document/maybe-reload document/poll-mail)]
    (if (:quit-for-restart state)
      state
      (keep-fit state))))

(defn card-model [state]
  (when-let [id (:detail-id state)]
    (detail/model (events/card-scene state) id)))

(defn views
  "What the page shows: the tab title, the window SVG, and the card."
  [state]
  (let [model (card-model state)]
    {:title (or (svg/window-title state) "UML viewer")
     :frame (markup/html (svg/frame state (dims state)))
     :card (if model
             {:title (or (get-in model [:class :name]) "Class")
              :svg (markup/html (svg/card model))}
             {:title "" :svg ""})}))

(defn- root-of [state]
  (overlay/metrics-root (:path state)))

(defn- open-menu [state items]
  (let [id (inc (or (get-in state [:menu :id]) 0))]
    (done (assoc state :menu {:id id :items items})
          {:menu {:id id
                  :items (mapv #(select-keys % [:label :prompt :default]) items)}})))

(defn- element-target [state sel]
  (let [id (:id sel)
        c (when id (hit/class-by-id (:scene state) id))
        component? (boolean
                     (or (events/layer-id sel)
                         (:dummy? c)
                         (seq (:contents c))
                         (= :package (:kind sel))))]
    (cond-> {:id id :kind (if component? :component :class)}
      (:ns c) (assoc :ns (:ns c))
      (:proposal-id state) (assoc :proposal-id (:proposal-id state)))))

(defn- element-menu [state sel]
  (open-menu state
             (mapv (fn [[label op]]
                     {:label label :agent op :target (element-target state sel)})
                   [["Refresh CRAP" :refresh-crap]
                    ["Refresh Mutation" :refresh-mutate]
                    ["Refresh All Mutation" :refresh-mutate-all]
                    ["Omit" :omit]])))

(defn- proposal-menu [state id]
  (let [pname (:name (policy/proposal-by-id (:doc state) id))]
    (open-menu state
               [{:label "Rename" :rename id :prompt "Rename proposal"
                 :default (str pname)}
                {:label "Delete" :delete id}])))

(defn- run-menu-item [state item value]
  (cond
    (:agent item)
    (do (sketch/request-agent! (root-of state) (:agent item) {:target (:target item)})
        state)

    (:rename item)
    (let [n (some-> value str/trim)]
      (if (seq n) (events/rename-proposal state (:rename item) n) state))

    (:delete item) (events/delete-proposal state (:delete item))
    :else state))

(defn- on-menu [state id index value]
  (let [menu (:menu state)
        item (when (= id (:id menu)) (get (:items menu) index))]
    (done (cond-> (dissoc state :menu)
            item (run-menu-item value)))))

(defn- regen-press [state ctx]
  (if-let [regenerate (:regenerate ctx)]
    (try
      (regenerate (:path state))
      (assoc (document/load-path (:path state)) :mail-status "Regenerated from source.")
      (catch Exception error
        (assoc state :mail-status (str "Regen failed: " (.getMessage error)))))
    (let [{:keys [woke?]} (sketch/request-regen! (root-of state))]
      (assoc state :mail-status (if woke?
                                  "Regen requested."
                                  "Regen queued; companion session not attached.")))))

(defn- sidebar-press [state right? x y w]
  (let [hit (events/inspector-hit state x y w)]
    (cond
      (and right? (= :proposal (:kind hit)))
      (proposal-menu state (:id hit))

      hit
      (let [next (events/on-inspector-press state hit)]
        (when (#{:real-diagram :proposal :new-proposal} (:kind hit))
          (sketch/mail-context! next))
        (done next))

      :else (done state))))

(defn- element-menu? [right? sel]
  (and right?
       (or (#{:class :child} (:kind sel))
           (events/layer-id sel))))

(defn- card-hit? [sel n]
  (or (#{:port :child} (:kind sel))
      (and (>= n 2) (= :class (:kind sel)))))

(defn- canvas-press [state ctx compact? right? x y n]
  (let [state (events/on-press (dissoc state :pinned) x y)
        sel (:selected state)
        layer (events/layer-id sel)]
    (cond
      (element-menu? right? sel) (element-menu state sel)

      ;; On a phone a tap is the primary gesture: tap a component to open
      ;; it, tap a class for its card. Double-tap still drills on desktop.
      (and compact? (= 1 n) layer)
      (done (events/drill state layer))

      (and compact? (= 1 n) (= :class (:kind sel)))
      (done (events/select-class state (:id sel)))

      (and (>= n 2) layer)
      (done (events/drill state layer))

      (and (= :port (:kind sel))
           (nil? (hit/class-by-id (events/card-scene state) (:id sel))))
      (done (events/jump-to state (:id sel)))

      (card-hit? sel n) (done (events/select-class state (:id sel)))
      :else (done state))))

(defn- on-press [state ctx x y right? n]
  (let [{:keys [w h compact?]} (dims state)
        state (dissoc state :menu)]
    (cond
      (and (not compact?) (events/regen-hit? x y w h))
      (done (regen-press state ctx))

      (and (not compact?) (>= x (- w layout/sidebar-w)))
      (sidebar-press state right? x y w)

      :else (canvas-press state ctx compact? right? x y n))))

(defn- source-page [ident]
  (when-let [{:keys [title body line]} (source/member-source ident)]
    {:title title
     :html (str "<!doctype html><meta charset=\"utf-8\"><title>"
                (markup/escape title) "</title>"
                "<style>body{margin:0;background:#f8fafc;color:#111;"
                "font:13px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}"
                "pre{margin:0;padding:12px 0}"
                "span{display:block;padding:0 16px}"
                ".hit{background:#fde68a;outline:1px solid #d97706}</style><pre>"
                (apply str
                       (map-indexed
                         (fn [i text]
                           (str "<span" (when (= (inc i) line)
                                          " class=\"hit\" id=\"here\"")
                                ">" (format "%5d  " (inc i))
                                (markup/escape text) "\n</span>"))
                         (str/split-lines body)))
                "</pre>")}))

(defn- source-reply [state ident]
  (done state (when-let [page (source-page ident)] {:source page})))

(defn- on-card-press [state y]
  (if-let [model (card-model state)]
    (let [rows (detail/rows model)]
      (cond
        (detail/source-at rows y)
        (source-reply state (detail/source-at rows y))

        (detail/rel-at rows y)
        (done (events/select-class state (detail/rel-at rows y)))

        :else (done state)))
    (done state)))

(defn- on-card-close [state]
  (done (events/close-detail state)))

(def ^:private key-names
  {"ArrowLeft" :left "ArrowRight" :right "ArrowUp" :up "ArrowDown" :down
   "Escape" :esc})

(defn key-of
  "Browser `KeyboardEvent.key` as the keyword events/on-key reads."
  [k]
  (or (key-names k)
      (when (= 1 (count (str k))) (keyword (str/lower-case k)))
      :unknown-key))

(defn- on-key [state k control?]
  (let [kw (key-of k)]
    (done
      (cond
        (and (= :esc kw) (:detail-id state) (not control?))
        (events/close-detail state)

        :else (events/on-key state kw (assoc (dims state) :control? control?))))))

(defn- on-resize [state w h]
  (let [state (assoc state :window {:w (max 320 w) :h (max 240 h)})]
    (done (keep-fit state))))

(defn- on-wheel [state amount horizontal?]
  (done (events/on-scroll state amount
                          (assoc (dims state) :horizontal? horizontal?))))

(defn- number-of [v]
  (try (Double/parseDouble (str v)) (catch Exception _ 0.0)))

(defn- int-of [v]
  (long (number-of v)))

(defn- yes? [v]
  (contains? #{"1" "true"} (str v)))

(defn handle
  "Apply one page event (string params from the browser) to `state`.
  `ctx` carries `:regenerate` (a source-regeneration function) when the
  viewer was started with one."
  [state ctx {:strs [type x y w h button count amount horizontal key ctrl id index value]}]
  (case type
    "resize" (on-resize state (int-of w) (int-of h))
    "move" (done (events/on-move state (number-of x) (number-of y)))
    "press" (on-press state ctx (number-of x) (number-of y)
                      (= "2" (str button)) (max 1 (int-of count)))
    "wheel" (on-wheel state (number-of amount) (yes? horizontal))
    "key" (on-key state key (yes? ctrl))
    "card-press" (on-card-press state (number-of y))
    "card-close" (on-card-close state)
    "menu" (on-menu state (int-of id) (int-of index) value)
    (done state)))
