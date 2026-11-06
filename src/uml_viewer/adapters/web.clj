(ns uml-viewer.adapters.web
  "The viewer as a local web page: an HTTP server, one state owned by a
  single thread, and frames pushed to every open page over SSE. The page
  sends clicks, keys, scrolls, and its window size back as form posts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.adapters.markup :as markup]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.adapters.viewer :as viewer]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.log :as log]
            [uml-viewer.domain.mailbox :as mailbox])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream IOException OutputStream]
           [java.net BindException Inet4Address InetAddress InetSocketAddress
            NetworkInterface URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CountDownLatch Executors ExecutorService
            ScheduledExecutorService TimeUnit]))

(def tick-ms 100)
(def ping-ms 15000)

(defonce !runtime
  (atom {:state nil :shown nil :clients #{} :keep-agent false :ctx {} :server nil}))

(defonce ^ScheduledExecutorService worker
  (Executors/newSingleThreadScheduledExecutor))

(defonce !tasks (atom []))

(defn parse-form
  "Map of a URL-encoded body's names to values."
  [body]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (when (seq k)
                    [(URLDecoder/decode k "UTF-8")
                     (URLDecoder/decode (or v "") "UTF-8")]))))
        (str/split (or body "") #"&")))

(def ^:private max-event-body
  "Bytes read from one POST /event; page events are small forms."
  16384)

(defn- strip-port
  "Host header without its port: `127.0.0.1:20001` → `127.0.0.1`,
  `[::1]:20001` → `::1`."
  [h]
  (if (str/starts-with? h "[")
    (subs h 1 (or (str/index-of h "]") (count h)))
    (first (str/split h #":"))))

(defn- literal-address
  "The IP literal `host`, parsed without any DNS lookup — a rebound domain
  must never resolve here into loopback."
  [host]
  (when (or (re-matches #"\d{1,3}(\.\d{1,3}){3}" host) (str/includes? host ":"))
    (try (InetAddress/getByName host) (catch Exception _ nil))))

(defn- local-address?
  "True when `a` is configured on this machine — the LAN address the page
  may have been opened with when `--host` is not loopback."
  [^InetAddress a]
  (try
    (let [want (.getHostAddress a)]
      (some (fn [^NetworkInterface ni]
              (when (.isUp ni)
                (some #(= want (.getHostAddress ^InetAddress %))
                      (enumeration-seq (.getInetAddresses ni)))))
            (enumeration-seq (NetworkInterface/getNetworkInterfaces))))
    (catch Exception _ nil)))

(defn local-host?
  "True when a Host/Origin header can only name this server: `localhost`,
  a loopback literal, or an address of this machine. DNS names never pass,
  so a rebound domain cannot reach the viewer."
  [host]
  (boolean
   (when (seq host)
     (let [h (str/lower-case (str/trim (strip-port (str/trim host))))]
       (or (= "localhost" h)
           (when-let [a (literal-address h)]
             (or (.isLoopbackAddress a)
                 (.isAnyLocalAddress a)
                 (boolean (local-address? a)))))))))

(defn same-origin-post?
  "True when a POST may touch `/event`: the Host header must name this
  machine, and the Origin header — which browsers send with every form POST —
  must be this server's own origin when present. A foreign page (including
  `Origin: null`, or another port on this machine) fails the Origin half; a
  rebound DNS name fails the Host half; a direct client without either (curl)
  passes on the Host check alone."
  [host-header origin]
  (let [h (some-> host-header str/trim str/lower-case)
        o (some-> origin str/trim str/lower-case)]
    (boolean (and (seq h)
                  (local-host? h)
                  (or (str/blank? o) (= o (str "http://" h)))))))

(defn read-body
  "At most `max` bytes of `in` as a UTF-8 string, or `::too-large` when the
  body overflows — capped while reading, so a lying Content-Length cannot
  make the JVM slurp an unbounded body."
  [in max]
  (let [buf (byte-array 1024)
        out (ByteArrayOutputStream.)]
    (loop []
      (let [n (.read ^java.io.InputStream in buf)]
        (cond
          (neg? n) (String. (.toByteArray out) StandardCharsets/UTF_8)
          (> (+ (.size out) n) max) ::too-large
          :else (do (.write out buf 0 n) (recur)))))))

(defn sse-message
  "One server-sent event named `event` carrying `data`."
  [event data]
  (str "event: " event "\n"
       (apply str (map #(str "data: " % "\n") (str/split (str data) #"\n" -1)))
       "\n"))

(defn changed-messages
  "SSE messages for the parts of `views` that differ from `shown`."
  [shown views]
  (cond-> []
    (not= (:title shown) (:title views))
    (conj (sse-message "title" (:title views)))

    (not= (:frame shown) (:frame views))
    (conj (sse-message "frame" (:frame views)))

    (not= (:card shown) (:card views))
    (conj (sse-message "card" (markup/json (:card views))))))

(defn- send! [^OutputStream out ^String msg]
  (.write out (.getBytes msg StandardCharsets/UTF_8))
  (.flush out))

(defn- broadcast!
  "Write `msgs` to every page; drop pages that have gone away."
  [msgs]
  (doseq [{:keys [out done] :as client} (:clients @!runtime)]
    (try
      (doseq [m msgs] (send! out m))
      (catch IOException _
        (swap! !runtime update :clients disj client)
        (.countDown ^CountDownLatch done)))))

(defn- push-views! []
  (let [{:keys [state shown]} @!runtime]
    (when state
      (let [views (viewer/views state)
            msgs (changed-messages shown views)]
        (swap! !runtime assoc :shown views)
        (when (seq msgs) (broadcast! msgs))))))

(defn- halt! []
  (System/exit 0))

(defn stop!
  "Stop the HTTP server, its thread pool, and its periodic tasks; release
  open SSE pages. Used by tests; normal shutdown goes through `quit!`. The
  shared single-thread `worker` keeps its (now idle) thread — the specs
  reuse this defonce JVM, and `quit!` ends it with the process."
  []
  (doseq [{:keys [done]} (:clients @!runtime)]
    (.countDown ^CountDownLatch done))
  (when-let [^HttpServer server (:server @!runtime)]
    (.stop server 0)
    (when-let [pool (.getExecutor server)]
      (when (instance? ExecutorService pool)
        (.shutdownNow ^ExecutorService pool)))
    (swap! !runtime assoc :server nil))
  (doseq [task @!tasks] (.cancel ^java.util.concurrent.Future task false))
  (reset! !tasks [])
  (swap! !runtime assoc :clients #{}))

(defn quit!
  "Stop the server, and the companion unless it should outlive this JVM."
  []
  (stop!)
  (when-not (:keep-agent @!runtime)
    (sketch/shutdown-children! (overlay/metrics-root (get-in @!runtime [:state :path]))))
  (halt!))

(defn- quit-for-restart! [state]
  (when state (document/save-session! state))
  (swap! !runtime assoc :keep-agent true)
  (quit!))

(defn step!
  "Run the page event `f` on the state thread and return its reply."
  [f]
  (.get (.submit worker ^Callable
                 (fn []
                   (let [{:keys [state reply]} (f (:state @!runtime))]
                     (swap! !runtime assoc :state state)
                     reply)))))

(defn- tick! []
  (try
    (let [state (viewer/tick (:state @!runtime))]
      (swap! !runtime assoc :state state)
      (if (:quit-for-restart state)
        (quit-for-restart! state)
        (push-views!)))
    (catch Throwable t
      (log/log-exception! t "web tick"))))

(defn- respond! [^HttpExchange ex status content-type ^bytes body]
  (.add (.getResponseHeaders ex) "Content-Type" content-type)
  (.add (.getResponseHeaders ex) "Cache-Control" "no-store")
  (.add (.getResponseHeaders ex) "X-Content-Type-Options" "nosniff")
  (.sendResponseHeaders ex status (if (pos? (alength body)) (alength body) -1))
  (with-open [out (.getResponseBody ex)]
    (.write out body)))

(defn- text! [ex status content-type s]
  (respond! ex status content-type (.getBytes (str s) StandardCharsets/UTF_8)))

(def ^:private assets
  {"/" ["index.html" "text/html; charset=utf-8"]
   "/app.js" ["app.js" "text/javascript; charset=utf-8"]
   "/app.css" ["app.css" "text/css; charset=utf-8"]})

(defn- asset! [ex [file content-type]]
  (text! ex 200 content-type (slurp (io/resource (str "uml_viewer/web/" file)))))

(defn- events! [^HttpExchange ex]
  (let [headers (.getResponseHeaders ex)
        done (CountDownLatch. 1)
        out (.getResponseBody ex)
        client {:out out :done done}
        {:keys [title frame card]} (:shown @!runtime)]
    (.add headers "Content-Type" "text/event-stream")
    (.add headers "Cache-Control" "no-store")
    (.sendResponseHeaders ex 200 0)
    (try
      (send! out (str (sse-message "title" (or title "UML viewer"))
                      (sse-message "frame" (or frame ""))
                      (sse-message "card" (markup/json (or card {})))))
      (swap! !runtime update :clients conj client)
      (catch IOException _ (.countDown done)))
    (.await done)
    (.close ex)))

(defn- event! [^HttpExchange ex]
  (let [body (read-body (.getRequestBody ex) max-event-body)]
    (if (= ::too-large body)
      (text! ex 413 "text/plain" "body too large")
      (let [params (parse-form body)]
        (if (= "quit" (get params "type"))
          (do (text! ex 200 "application/json" "{}")
              (quit!))
          (let [ctx (:ctx @!runtime)
                reply (step! #(viewer/handle % ctx params))]
            (text! ex 200 "application/json" (markup/json (or reply {})))))))))

(defn- route! [^HttpExchange ex]
  (let [path (.getPath (.getRequestURI ex))
        method (.getRequestMethod ex)]
    (try
      (cond
        (and (= "GET" method) (assets path)) (asset! ex (assets path))
        (and (= "GET" method) (= "/events" path)) (events! ex)
        (and (= "POST" method) (= "/event" path))
        (if (same-origin-post? (.getFirst (.getRequestHeaders ex) "Host")
                               (.getFirst (.getRequestHeaders ex) "Origin"))
          (event! ex)
          (text! ex 403 "text/plain" "forbidden"))
        (and (= "GET" method) (= "/health" path))
        (text! ex 200 "text/plain" "ok")
        :else (text! ex 404 "text/plain" "not found"))
      (catch Throwable t
        (log/log-exception! t (str method " " path))
        (try (text! ex 500 "text/plain" "error") (catch Throwable _))))))

(defn preferred-port
  "A port in 20000–29999 fixed by the project, so a restarted viewer
  comes back where its open pages already point."
  [root]
  (+ 20000 (mod (hash (.getCanonicalPath (io/file root))) 10000)))

(defn- bind [host port]
  (try
    (HttpServer/create (InetSocketAddress. (str host) (int port)) 0)
    (catch BindException _ nil)
    (catch IOException _ nil)))

(defn- serve! [host port root]
  (let [preferred (or port (preferred-port root))
        server (or (some #(bind host %) (range preferred (+ preferred 20)))
                   (bind host 0))]
    (when-not server
      (throw (ex-info (str "could not bind " host ":" preferred) {:host host :port preferred})))
    (.createContext server "/" (reify HttpHandler (handle [_ ex] (route! ex))))
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    server))

(defn web-file [root]
  (io/file (mailbox/dir root) "web.edn"))

(defn- lan-address []
  (try
    (some (fn [^NetworkInterface ni]
            (when (.isUp ni)
              (some (fn [^InetAddress a]
                      (when (and (instance? Inet4Address a)
                                 (not (.isLoopbackAddress a))
                                 (.isSiteLocalAddress a))
                        (.getHostAddress a)))
                    (enumeration-seq (.getInetAddresses ni)))))
          (enumeration-seq (NetworkInterface/getNetworkInterfaces)))
    (catch Exception _ nil)))

(defn- local-url [port]
  (str "http://127.0.0.1:" port "/"))

(defn- lan-url [port]
  (when-let [ip (lan-address)]
    (str "http://" ip ":" port "/")))

(defn open-browser! [u]
  (let [cmd (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
              "open" "xdg-open")]
    (try (.start (ProcessBuilder. (into-array String [cmd u])))
         (catch Exception e (log/log-exception! e "open browser")))))

(defn start!
  "Serve `path` and open it in the browser. `opts`:
  `:restart?`, `:standalone?` (load now, no companion), `:port`,
  `:host`, `:open?`, `:regenerate`."
  [path source-impl opts]
  (let [root (overlay/metrics-root path)
        restart? (:restart? opts)
        standalone? (:standalone? opts)]
    (when (and (not standalone?) (not restart?))
      (sketch/open-in-terminal! root))
    (swap! !runtime assoc
           :keep-agent (boolean standalone?)
           :ctx {:source source-impl :regenerate (:regenerate opts)}
           :state (viewer/initial-state path (boolean (or restart? standalone?))))
    (let [host (or (:host opts) "127.0.0.1")
          server (serve! host (:port opts) root)
          port (.getPort (.getAddress server))]
      (swap! !runtime assoc :server server)
      (io/make-parents (web-file root))
      (spit (web-file root) (pr-str {:port port :host host :url (local-url port)}))
      (reset! !tasks
              [(.scheduleWithFixedDelay worker ^Runnable tick! 0 tick-ms TimeUnit/MILLISECONDS)
               (.scheduleWithFixedDelay worker ^Runnable #(broadcast! [": ping\n\n"])
                                        ping-ms ping-ms TimeUnit/MILLISECONDS)])
      (let [local (local-url port)
            lan (when-not (#{"127.0.0.1" "localhost" "::1"} host) (lan-url port))]
        (when (:open? opts) (open-browser! local))
        (println "Viewer at" local)
        (when lan (println "On the LAN:" lan))
        (println "Open pages stay connected; restart reuses the port in .uml-viewer/web.edn.")
        local))))
