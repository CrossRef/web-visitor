(ns web-visitor.http
  "Web fetching."
  (:require [org.httpkit.client :as http]
            [robert.bruce :refer [try-try-again]]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]))

(def redirect-depth 4)

(def user-agent "CrossrefEventDataBot (https://www.crossref.org/services/event-data/;mailto:labs@crossref.org)")

(def headers {"Referer" "https://eventdata.crossref.org"
              "User-Agent" user-agent})

(def timeout-ms
  "Timeout for HTTP requests."
  30000)

(def deref-timeout-ms
  "Last-ditch timeout for derefing result. This is a safety-valve to avoid threads hanging."
  60000)

(defn fetch-throwing
  "Fetch the content at a URL as a string, following redirects.
   Don't accept cookies.
   Return seq of {:url :status :body} in order of redirect."
  ([url] (fetch-throwing url []))
  ([url history]
  
  (try
    (if (> (count history) redirect-depth)
      history
      (let [result (deref
                     (http/get
                       url
                       {:follow-redirects false
                        :headers headers
                        :as :text
                        :timeout timeout-ms
                        :throw-exceptions true})
                     deref-timeout-ms
                     nil)

            new-history (conj history {:url url
                                       :status (:status result)
                                       :body (:body result)})

            location-header (-> result :headers :location)]
        
        ; Timeout results in nil, in which case return nil.
        (when result

          (condp = (:status result)
            200 new-history
            ; Weirdly some Nature pages return 401 with the content. http://www.nature.com/nrendo/journal/v10/n9/full/nrendo.2014.114.html
            401 new-history
            301 (fetch-throwing location-header new-history)
            303 (fetch-throwing location-header new-history)
            302 (fetch-throwing location-header new-history)
            nil))))

    (catch Exception exception
      (do
        (log/error "Exception" exception)
        (.printStackTrace exception)
        nil)))))

(defn fetch
  [url]
  (try-try-again
    {:sleep 5000 :tries 2}
    #(fetch-throwing url)))
