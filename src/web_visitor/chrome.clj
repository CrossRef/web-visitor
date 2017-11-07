(ns web-visitor.chrome
  (:require clojure.pprint)
  (:require [clojure.tools.logging :as log]
            [web-visitor.http :as http]
            [clojure.data.json :as json]
            [overtone.at-at :as at-at]
            [clj-time.core :as clj-time])
  (:import [io.webfolder.cdp Launcher]
           [io.webfolder.cdp.session Session SessionFactory]
           [io.webfolder.cdp.listener EventListener]
           [io.webfolder.cdp.event Events]
           [io.webfolder.cdp.type.page ResourceType]
           [io.webfolder.cdp.event.page NavigationRequested]
           [io.webfolder.cdp.event.network RequestWillBeSent])
  (:gen-class))

; https://gist.github.com/megakorre/3137983
(defmacro fi
  [interface args & code]
  (let [interface-type (.getMapping *ns* interface)

        methods        (-> (.getMethods interface-type)
                           seq)
        method-sym     (.getName (first methods))]

    (when-not (= (count methods) 1)
      (throw (new Exception "cant take a interface with more then one method")))
    
    `(proxy [~interface] []
       (~method-sym ~args
         ~@code))))

(def launcher (atom nil))
(def session-factory (atom nil))
(def session (atom nil))

(defn get-chrome-processes
  "Return a list of Process IDs (as strings) for running Chrome procesess."
  []
  (let [ps (.exec (Runtime/getRuntime) "ps aux")]
    (with-open [rdr (clojure.java.io/reader (.getInputStream ps))]
      (let [chrome-lines (filter #(.contains % "/usr/bin/google-chrome") (line-seq rdr))
            chrome-pids (map #(second (re-find #"^[^ ]+\s+(\d+)\s" %)) chrome-lines)]
        ; Realize seq before the stream is closed!
        (doall chrome-pids)))))

(defn kill-chrome!
  []
   (log/info "Killing Chrome. Get process IDs...")
   (let [processes (get-chrome-processes)]
    (log/info "Killing Chrome processes" processes "...")
     (doseq [pid processes]
       (log/info "Killing process" pid)
       (.exec (Runtime/getRuntime) (str "kill -9 " pid))))
   (log/info "Finished killing Chrome"))

(defn teardown!
  "When something goes wrong communicating with the Chrome process, reset all state and kill Chrome."
  []
  ; Don't try to call close on these, it will hang indefinitely under these circumstances.
  (reset! session-factory nil)
  (reset! session nil)
  (reset! launcher nil)
  (kill-chrome!))

(def last-watchdog (atom nil))

; Generous timeout for Chrome to get itself set up and return something.
; Only for exceptions.
(def watchdog-timeout
  (clj-time/seconds 60))

(defn start-watchdog!
  "Keep an eye on the most recent activity, bomb out if nothing happened."
  []
  (log/info "Start watchdog")
  (let [schedule-pool (at-at/mk-pool)]
  (at-at/every 10000
    (fn []
      ; If it's nil then we've not set it, or have un-set it, so ignore.
      (when @last-watchdog
        (log/info "Check watchdog...")
        (when (clj-time/before?
                         @last-watchdog
                         (clj-time/ago watchdog-timeout))
          (log/error "Input timed out! Last timeout was at" (str @last-watchdog) ", longer than" (str watchdog-timeout) "ago")
          (kill-chrome!)
          (reset! @last-watchdog nil))))
    schedule-pool)))

(defn get-factory
  "Return a session factory, launching Chrome if needed."
  []
  (when-not @launcher
    (log/info "Create launcher...")
    (reset! launcher (Launcher.)))

  (let [the-launcher @launcher]
      (let [the-session-factory (.launch the-launcher ["--headless" "--disable-gpu" "--remote-debugging-address=0.0.0.0" "--remote-debugging-port=9222"])]
        (reset! session-factory the-session-factory)))
    @session-factory)

(defn get-session
  []
  (or @session
      (reset! session (.create (get-factory)))))

(defn fetch-throwing [session url]
  (log/info "Fetch url" url "using session" session)
  (let [command (.getCommand session)
        network (.getNetwork command)
        ; Keep a log of all ResponseReceived objects
        ; Some will be in the the non-main frame (e.g. iframes)
        ; Filter later
        all-requested-documents (atom [])]
              

    (.enable network)
    (.setUserAgentOverride network http/user-agent)

    ; Capture all network request / response along with frame unmber.
    (.addEventListener session (fi EventListener [e d]
      (when (and (= Events/NetworkRequestWillBeSent e)
                 (= (type d) RequestWillBeSent))
        (when-let [redirect-response (.getRedirectResponse d)]
          (when (= ResourceType/Document (.getType d))
            (swap! all-requested-documents conj
              {:url (.getUrl redirect-response)
               :body nil
               :timestamp (.getRequestTime (.getTiming redirect-response))
               :status (int (.getStatus redirect-response))
               :frame-id (.getFrameId d)}))))
      (when (= Events/NetworkResponseReceived e)

        (let [response (.getResponse d)
              resource-type (.getType d)
              frame-id (.getFrameId d)]

          (when (= resource-type ResourceType/Document)
            (let [url (.getUrl response)
                  status (int (.getStatus response))]
              (swap! all-requested-documents conj {:url url
                :status status
                :body nil
                :timestamp (.getRequestTime (.getTiming response))
                :frame-id frame-id})))))))
    
    ; Cleanse the palatte.
    (.navigate session "about:blank")
    (.waitDocumentReady session)

    (.navigate session url)
    
    ; If the document isn't ready after 10 seconds, that's no big issue, we can still get the content.
    ; We some kind of reasonable timeout.
    (try 
      (.waitDocumentReady session 10000)

      (catch io.webfolder.cdp.exception.LoadTimeoutException ex
        (log/info "Timeout from URL" url)))

    ; Give it 5 seconds after the DOM is loaded for any  JS to execute.
    (Thread/sleep 5000)


    ; We only want those documents that were requested in the main frame.
    ; The Session's main frame ID is only available after we started the navigation
    ; (but before we had to register the event listener).
    ; This should match all HTML and XML documents.
    ; There maybe a NPE from (.getProperty session), in which case return nil.
    (let [dom-content (try (.getProperty session ":root" "outerHTML") (catch Exception e (do (log/error "NPE getting HTML for" url) nil)))
          session-frame-id (.getFrameId session)
          trace (filter #(= (:frame-id %) session-frame-id) @all-requested-documents)]

      ; If we got nothing, end here.
      (when >= (count trace) 1)
        (let [sorted (sort-by :timestamp trace)
              with-keys (map #(select-keys % [:url :status :body]) sorted)]
          
          ; If there's no trace, return nil, not an empty list.
          (if (empty? with-keys)
            nil
            (let [; We only have the body content for the last item, so we fake nils and then assoc in the last item.
                  last-item (assoc (last with-keys) :body dom-content)
                  rest-items (drop-last 1 with-keys)
                  
                  ; Make sure it's realized becuase we're in a resource-managed context.
                  result (doall (concat rest-items [last-item]))]
                result))))))


(defn fetch
  [url]
  (log/info "Set watchdog")
  (reset! last-watchdog (clj-time/now))

  (try
    (log/info "Fetch 1 " url)
    (fetch-throwing (get-session) url)

    (catch Exception e1
      (do
        (log/error "First error fetching" url e1)
        (log/info "Try with new session...")
        (teardown!)

        (try
          (log/info "Fetch 2 " url)
          (reset! last-watchdog (clj-time/now))
          (fetch-throwing (get-session) url)

          (catch Exception e2
            (do
              (log/error "Second error fetching" url e2)

              nil)))))


    ; Call off the dogs.
    (finally
      (log/info "Reset watchdog")
      (reset! last-watchdog nil))))
