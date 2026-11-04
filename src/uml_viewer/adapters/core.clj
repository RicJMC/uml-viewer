(ns uml-viewer.adapters.core
  (:require [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.adapters.web :as web]))

(def ^:dynamic *regenerate*
  "Source regeneration hook installed by the entry point; web mode uses
  it for the Regen button when no companion is attached."
  nil)

(def help-text
  (str "Usage: clj -M:run [options] [edn-file]\n"
       "\n"
       "  edn-file          Diagram to watch (default: examples/library.edn).\n"
       "                    A fresh start waits for the companion agent (Grok or\n"
       "                    Pi; UML_VIEWER_AGENT=pi|grok picks one) to send\n"
       "                    :display unless the associated agent recycles\n"
       "                    the window with :uml-viewer-restart, or you press R.\n"
       "\n"
       "  --restart         Associated agent only (via :uml-viewer-restart).\n"
       "                    New JVM, keep the existing companion tmux session.\n"
       "                    Reloads the last view (depth, pan, zoom, proposal).\n"
       "                    Do not use this if no companion is attached.\n"
       "\n"
       "  --standalone      Load without starting or stopping a companion agent.\n"
       "  --screenshot=PATH Save one desktop frame to PATH, then exit. Implies\n"
       "                    --standalone; useful for docs and visual review.\n"
       "\n"
       "  --web             Serve the viewer in the browser instead of a desktop\n"
       "                    window. The page works on a phone over the LAN.\n"
       "  --host HOST       Web bind address (default 127.0.0.1). Use 0.0.0.0 to\n"
       "                    reach it from a phone or tablet on the same network.\n"
       "  --port N          Web port (default: a fixed per-project port in\n"
       "                    20000-29999, so open pages reconnect after restart).\n"
       "  --no-open         Do not open the browser.\n"
       "  -h, --help        Print this help and exit.\n"))

(def screenshot-prefix "--screenshot=")

(defn- screenshot-path [args]
  (some (fn [arg]
          (when (and (string? arg)
                     (.startsWith ^String arg screenshot-prefix))
            (subs arg (count screenshot-prefix))))
        args))

(defn- flag-value [args flag]
  (some (fn [[a b]] (when (= a flag) b))
        (partition-all 2 1 (keep identity args))))

(defn- without-flags [args]
  (loop [[a & more] args acc []]
    (cond
      (nil? a) acc
      (or (#{"--help" "-h" "--restart" "--standalone" "--web" "--no-open"} a)
          (and (string? a) (.startsWith ^String a screenshot-prefix)))
      (recur more acc)
      (#{"--host" "--port"} a) (recur (rest more) acc)
      :else (recur more (conj acc a)))))

(defn parse-args
  "EDN path and flags. `--restart` skips spawning a new agent; `--web`
  serves the diagram over HTTP."
  [args]
  (let [args (vec (keep identity args))
        help? (boolean (some #{"--help" "-h"} args))
        standalone? (boolean (some #{"--standalone"} args))
        restart? (boolean (some #{"--restart"} args))
        web? (boolean (some #{"--web"} args))
        no-open? (boolean (some #{"--no-open"} args))
        host (flag-value args "--host")
        port (some-> (flag-value args "--port") parse-long)
        screenshot (screenshot-path args)
        path (first (without-flags args))]
    (cond-> {:help? help?
             :restart? restart?
             :path (or path "examples/library.edn")}
      standalone? (assoc :standalone? true)
      web? (assoc :web? true :host host :port port :no-open? no-open?)
      (and screenshot (seq screenshot))
      (assoc :screenshot screenshot :standalone? true))))

(defn start!
  "Launch the viewer. `source-impl` satisfies `LanguageSource`."
  [source-impl & args]
  (let [{:keys [path restart? standalone? help? screenshot web? host port no-open?]}
        (parse-args args)]
    (when screenshot
      (swap! sketch/!bridge assoc :screenshot screenshot))
    (if help?
      (do (print help-text) :help)
      (if web?
        (do
          (web/start! path source-impl
                      {:restart? restart?
                       :standalone? standalone?
                       :host host
                       :port port
                       :open? (not no-open?)
                       :regenerate *regenerate*})
          (println "Watching" path))
        (do
          (sketch/start! path source-impl (boolean (or restart? standalone?)))
          (println "Watching" path)
          (println "Double-click a box for its card. Scroll to pan (Shift-scroll for horizontal). Ctrl+/− zoom; Ctrl+0 100%; F fits all. R reloads. Click the real diagram above Proposals, or a proposal to show it."))))))
