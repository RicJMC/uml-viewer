(ns uml-viewer.adapters.core
  (:require [uml-viewer.adapters.sketch :as sketch]))

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
       "  --screenshot=PATH Save one frame to PATH, then exit. Implies\n"
       "                    --standalone; useful for docs and visual review.\n"
       "  -h, --help        Print this help and exit.\n"))

(def screenshot-prefix "--screenshot=")

(defn- screenshot-path [args]
  (some (fn [arg]
          (when (and (string? arg)
                     (.startsWith ^String arg screenshot-prefix))
            (subs arg (count screenshot-prefix))))
        args))

(defn parse-args
  "EDN path and flags. `--restart` skips spawning a new agent."
  [args]
  (let [args (vec (keep identity args))
        help? (boolean (some #{"--help" "-h"} args))
        standalone? (boolean (some #{"--standalone"} args))
        restart? (boolean (some #{"--restart"} args))
        screenshot (screenshot-path args)
        path (->> args
                  (remove #{"--help" "-h" "--restart" "--standalone"})
                  (remove #(and (string? %) (.startsWith ^String % screenshot-prefix)))
                  first)]
    (cond-> {:help? help?
             :restart? restart?
             :path (or path "examples/library.edn")}
      standalone? (assoc :standalone? true)
      (and screenshot (seq screenshot))
      (assoc :screenshot screenshot :standalone? true))))

(defn start!
  "Launch the viewer. `source-impl` satisfies `LanguageSource`."
  [source-impl & args]
  (let [{:keys [path restart? standalone? help? screenshot]} (parse-args args)]
    (when screenshot
      (swap! sketch/!bridge assoc :screenshot screenshot))
    (if help?
      (do (print help-text) :help)
      (do
        (sketch/start! path source-impl (boolean (or restart? standalone?)))
        (println "Watching" path)
        (println "Double-click a box for its card. Scroll to pan (Shift-scroll for horizontal). Ctrl+/− zoom; Ctrl+0 100%; F fits all. R reloads. Click the real diagram above Proposals, or a proposal to show it.")))))
