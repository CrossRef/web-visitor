(ns web-visitor.chrome
  (:require clojure.pprint)
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json])
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

(defn fetch [factory url]
  (with-open [session (.create factory)]
    (let [command (.getCommand session)
          network (.getNetwork command)
          ; Keep a log of all ResponseReceived objects
          ; Some will be in the the non-main frame (e.g. iframes)
          ; Filter later
          all-requested-documents (atom [])]
                
      (.enable network)
      (.setUserAgentOverride network "CrossrefEventDataBot")

      ; Capture all network request / response along with frame unmber.
      (.addEventListener session (fi EventListener [e d]
        (when (and (= Events/NetworkRequestWillBeSent e)
                   (= (type d) RequestWillBeSent))
          (when-let [redirect-response (.getRedirectResponse d)]
            (when (= ResourceType/Document (.getType d))
              (swap! all-requested-documents conj
                {:url (.getUrl redirect-response)
                 :status (.getStatus redirect-response)
                 :frame-id (.getFrameId d)}))))

        (when (= Events/NetworkResponseReceived e)
          (let [response (.getResponse d)
                resource-type (.getType d)
                frame-id (.getFrameId d)]

            (when (= resource-type ResourceType/Document)
              (let [url (.getUrl response)
                    status (.getStatus response)]
                (swap! all-requested-documents conj {:url url
                  :status status
                  :frame-id frame-id})))))))

      (.navigate session url)
      
      (Thread/sleep 10000)
      
      (.waitDocumentReady session)

      ; We only want those documents that were requested in the main frame.
      ; The Session's main frame ID is only available after we started the navigation
      ; (but before we had to register the event listener).
      (let [dom-content (.getProperty session "/html" "outerHTML")
            session-frame-id (.getFrameId session)
            trace (filter #(= (:frame-id %) session-frame-id) @all-requested-documents)]

        {:body dom-content
         :trace trace}))))

(defn one [url filepath]
  "Snapshot one URL to the given file path in a single session."
  (with-open [factory (SessionFactory. "localhost" 9222)]
    (let [result (fetch factory url)]
      (spit filepath (json/write-str result)))))
