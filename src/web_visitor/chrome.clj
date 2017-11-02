(ns web-visitor.chrome
  (:require clojure.pprint)
  (:require [clojure.tools.logging :as log]
            [web-visitor.http :as http]
            [clojure.data.json :as json]
            [config.core :refer [env]]
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
   (when-let [the-session-factory @session-factory]
    (.close the-session-factory))

   (reset! session-factory nil)
   (reset! launcher nil)
   (doseq [pid (get-chrome-processes)]
     (log/info "Killing process" pid)
     (.exec (Runtime/getRuntime) (str "kill -9 " pid))))

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


(defn fetch-throwing [factory url]
  (with-open [session (.create factory)]
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
      (.navigate session url)
      ; Give it 5 seconds after the DOM is loaded.
      (.waitDocumentReady session)
      (Thread/sleep 5000)

      ; We only want those documents that were requested in the main frame.
      ; The Session's main frame ID is only available after we started the navigation
      ; (but before we had to register the event listener).
      ; This should match all HTML and XML documents.
      (let [dom-content (.getProperty session ":root" "outerHTML")
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
                  result)))))))

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

(defn fetch
  [url]
  (log/info "Set watchdog")
  (reset! last-watchdog (clj-time/now))

  (try
    (log/info "Fetch" url)
    (fetch-throwing (get-factory) url)

    (catch Exception e
      (do
        (log/error "First error fetching" url e)
        (log/info "Killing Chrome")
        (kill-chrome!)
        (log/info "Getting new Chrome process.")
        
        (let [new-factory (get-factory)]
          (log/info "New Chrome PID:" (get-chrome-processes))

        (try
          (log/info "Try again fetch" url)
          (fetch-throwing new-factory url)

          (catch Exception e2
            (do
              (log/error "Second error fetching" url e2)
              nil)))
        nil)))

    ; Call off the dogs.
    (finally
      (log/info "Reset watchdog")
      (reset! last-watchdog nil))))
