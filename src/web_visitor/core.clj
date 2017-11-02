(ns web-visitor.core
  (:require [web-visitor.sample-crossref :as sample-crossref]
            [web-visitor.sample-datacite :as sample-datacite]
            [web-visitor.http :as http]
            [web-visitor.html :as html]
            [web-visitor.aggregate :as aggregate]
            [web-visitor.handle :as handle]
            [crossref.util.doi :as cr-doi]
            [clojure.tools.logging :as log]
            [web-visitor.chrome :as chrome]
            [clj-time.format :as clj-time-format]
            [clj-time.core :as clj-time]
            [clojure.data.json :as json]
            [config.core :refer [env]]
            [event-data-common.storage.s3 :as s3]
            [event-data-common.storage.store :as store]
            [taoensso.timbre :as timbre])
  (:import [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.clients.consumer KafkaConsumer ConsumerRecords]
           [io.webfolder.cdp.session Session SessionFactory]
           [java.util UUID])
  (:gen-class))

(timbre/merge-config!
  {:level :info
   :ns-blacklist ["cdp4j.flow"]})

(def kafka-producer
  (delay
    (KafkaProducer.
      {"bootstrap.servers" (:global-kafka-bootstrap-servers env) ; "kafka:9092";  
       "acks" "all"
       "retries" (int 5)
       "key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
       "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"})))


(def data-store
  (delay
    (s3/build (:visitor-s3-key env)
              (:visitor-s3-secret env)
              (:visitor-s3-region-name env)
              (:visitor-s3-bucket-name env))))

(def storage-prefix "visitor")

(def date-format
  "2017-10-31T09:26:15 for IDs"
  (clj-time-format/formatters :date-hour-minute-second))

(def date-time-format
  "2017-10-31T09:26:15.575Z for timestamps."
  (clj-time-format/formatters :date-time))


(defn generate-id
  []
  (str
    (clj-time-format/unparse date-format (clj-time/now))
    "-"
    (UUID/randomUUID)))

(defn prefix-for-package-type
  [package-type]
  (str storage-prefix "/" (name package-type)))

(defn path-for
  [package-type id]
  (str (prefix-for-package-type package-type) "/" id))

(defn save
  "Save package to store and enqueue for downstream processing.
   topic-name optional, omit to not send to Kafka."
  [id package-type topic-name package-structure]
  (log/info "Save" package-type "/" id)
  (let [json (json/write-str package-structure)
        storage-path (path-for package-type id)]

    (store/set-string @data-store storage-path json)

    (when topic-name
      (.send @kafka-producer
        (ProducerRecord. topic-name id storage-path)))))

(defn run-sample-crossref-once
  []
  "Collect a random sample of DOIs, weighted by publisher member, send to Kafka topic."
  (log/info "Running a sample batch from Crossref.")
  (let [dois (sample-crossref/sample-all)]
    (doseq [doi dois]
      (let [id (generate-id)
            package {:id id
                     :doi (cr-doi/normalise-doi doi)
                     :source "crossref-api"}]
        (save id :sample (:visitor-sample-topic env) package)))))

(defn run-sample-crossref-continuous
  []
  (loop []
    (run-sample-crossref-once)
    (recur)))


(defn run-sample-datacite-once
  []
  "Collagainect a random sample of DOIs, weighted by prefix member, send to Kafka topic."
  (log/info "Running a sample batch from DataCite.")
  (let [dois (sample-datacite/sample-all)]
    (doseq [doi dois]
      (let [id (generate-id)
            package {:id id
                     :doi (cr-doi/normalise-doi doi)
                     :source "datacite-api"}]
        (save id :sample (:visitor-sample-topic env) package)))))

(defn run-sample-datacite-continuous
  []
  (loop []
    (run-sample-datacite-once)
    (recur)))

(defn subscribe
  "Subscribe to topic name, specifying the type of package that we are *producing*.
   Run callback on [id package-content] when we've not already processed it."
  [input-topic-name output-topic-name output-package-type f]
  (let [consumer (KafkaConsumer. 
                   {"bootstrap.servers" (:global-kafka-bootstrap-servers env)
                    "group.id" (name output-package-type)
                    "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                    "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                    "auto.offset.reset" "earliest"})]
    
    (log/info "Subscribing to" input-topic-name "...")
    
    (.subscribe consumer (list input-topic-name))
    (log/info "Polling" input-topic-name "...")
      (loop []
        (let [^ConsumerRecords records (.poll consumer (int 10000))]
          (log/info "Got" (.count records) "records from" input-topic-name "partitions:" (map str (.partitions records)))
          (dorun
            (map
              (fn [record]
                (let [id (.key record)
                      s3-path (.value record)

                      existing-output-path (path-for output-package-type id)
                      existing-output (store/get-string @data-store existing-output-path)
                      has-existing-output? (some? existing-output)]

                  ; If we already processed this, take no further action.
                  (when has-existing-output?
                    (log/info "Skip" output-package-type "/" id))

                  ; Lots of nil handling in case the stream has IDs that were never saved.
                  (when-not has-existing-output?
                    (log/info "Process" output-package-type "/" id)
                    (let [input-content (store/get-string @data-store s3-path)
                          parsed (when input-content (json/read-str input-content :key-fn keyword))
                          result (when parsed (f id parsed))]
                      
                      (when result
                        (save (:id parsed) output-package-type output-topic-name result))))))

                records)))
        (recur))))


(defn all-keys-for-type
  "Rescan storage back into Kafka queue for reprocessing."
  [package-type]
  (let [prefix (prefix-for-package-type package-type)
        ; We know the path is the concatenation of the prefix and the ID.
        prefix-length (inc (.length prefix))
        storage-paths (store/keys-matching-prefix @data-store prefix)]
  ; Seq of [id path]
  (map #(vector (.substring % prefix-length) %) storage-paths)))


(defn requeue
  "Rescan storage back into Kafka queue for reprocessing."
  [topic-name package-type]
  (log/info "Requeue type:" (name package-type) "topic:" topic-name)
  (doseq [[id storage-path] (all-keys-for-type)]
      (log/info "Requeue " id)
      (.send @kafka-producer
        (ProducerRecord. topic-name id storage-path))))



(defn echo
  [input-topic-name]
    (let [consumer (KafkaConsumer. 
                   {"bootstrap.servers" (:global-kafka-bootstrap-servers env)
                    "group.id" (str input-topic-name "echo1")
                    "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                    "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                    "auto.offset.reset" "earliest"})]
    
    (log/info "Subscribing to" input-topic-name "...")
    
    (.subscribe consumer (list input-topic-name))
    (log/info "Polling" input-topic-name "...")
      (loop []
        (let [^ConsumerRecords records (.poll consumer (int 10000))]
          (log/info "Got" (.count records) "records from" input-topic-name "partitions:" (map str (.partitions records))))
        (recur))))


(defn perform-observation
  "Accept a sample input package, return an observation package."
  [id sample-input]

  (let [doi (:doi sample-input)
        resource-url (handle/resolve-doi doi)

        ; Reason to skip URL? If not, nil. If so, include reason.
        skip (cond
               ; Yes, this happened.
               (nil? resource-url) :nil-resource

               ; PDFs are often large files.
               ; There is nothing we can do with PDFs
               ; No good can come from archiving them.
               (.endsWith resource-url ".pdf") :pdf
               :default nil)
        
        ; Retrive objects of {:url :status :body}
        http-trace (when-not skip (http/fetch resource-url))

        ; And the final URL arrived at.
        http-url (when-not skip (-> http-trace last :url))
        http-status (when-not skip (-> http-trace last :status))

        browser-trace (when-not skip (chrome/fetch resource-url))
        browser-url (when-not skip (-> browser-trace last :url))
        browser-status (when-not skip (-> browser-trace last :status))

        now (clj-time/now)
        timestamp (clj-time-format/unparse date-time-format now)]

    {:id id
     :sample-input sample-input
     :timestamp timestamp
     :resource-url resource-url
     :skip skip
     :http-trace http-trace
     :http-url http-url
     :http-status http-status

     :browser-trace browser-trace
     :browser-url browser-url
     :browser-status browser-status}))

(defn run-observation-continuous
  []
  (chrome/start-watchdog!)
  (subscribe
    (:visitor-sample-topic env)
    (:visitor-observation-topic env)
    :observation
    perform-observation))

(defn try-get-domain
  [url-str]
  (try
    (.getHost (java.net.URI. url-str))
    (catch Exception _ nil)))

(defn perform-extraction
  [id observation-input]
  (let [doi (-> observation-input :sample-input :doi)
        
        browser-meta-summary (html/create-doi-summary doi (-> observation-input :browser-trace last :body))
        http-meta-summary (html/create-doi-summary doi (-> observation-input :http-trace last :body))

        http-meta-unambiguous (boolean
                                (and (-> http-meta-summary :conflicts empty?)
                                     (-> http-meta-summary :matches not-empty)))

        browser-meta-unambiguous (boolean
                                   (and (-> browser-meta-summary :conflicts empty?)
                                        (-> browser-meta-summary :matches not-empty)))

        http-url (-> observation-input :http-trace last :url)
        browser-url (-> observation-input :browser-trace last :url)

        http-url-domain (try-get-domain http-url)
        browser-url-domain (try-get-domain browser-url)

        http-redirects (-> observation-input :http-trace count)
        browser-redirects (-> observation-input :browser-trace count)

        ; Every URL we have seen attached to this DOI.
        all-urls (distinct
                   (concat
                     (map :url (-> observation-input :browser-trace))
                         (map :url (-> observation-input :http-trace))
                         [(:resource-url observation-input)]))

        all-domains (distinct (keep try-get-domain all-urls))]

    {:id id
     ; Don't include the full body text, as this is 
     ; available from the observation in storage directly.
     :observation-input (dissoc observation-input :browser-trace :http-trace)
     :doi doi

     :browser {:redirects browser-redirects
               :url browser-url
               :url-domain browser-url-domain
               :meta-summary browser-meta-summary
               :meta-unambiguous browser-meta-unambiguous}

     :http {:redirects http-redirects
            :url http-url
            :url-domain http-url-domain
            :meta-summary http-meta-summary
            :meta-unambiguous http-meta-unambiguous}

      :all-urls all-urls
      :all-domains all-domains}))

(defn run-extraction-continuous
  []
  (subscribe
    (:visitor-observation-topic env)
    (:visitor-extraction-topic env)
    :extraction
    perform-extraction))

(defn run-aggregation
  "Run aggregation and return structure."
  []
  (let [extraction-storage-paths (all-keys-for-type :extraction)]
    (loop [acc {}
           paths extraction-storage-paths]
      (if (empty? paths)
        acc
        (let [[id storage-path] (first paths)
              extraction-data (store/get-string @data-store storage-path)
              parsed (when extraction-data (json/read-str extraction-data :key-fn keyword))
              acc (aggregate/aggregate acc parsed)
              ; Replace this each time so we know how far we got.
              acc (assoc acc :last-id-seen id)]
          (log/info "Aggregated" id)
          (recur acc (rest paths)))))))

(defn run-aggregation-once
  "Run and save."
  []
  (let [result (run-aggregation)
        id (:last-id-seen result)]
    (log/info "Start to save aggregation" id)
    (store/set-string @data-store (path-for :aggregation id) (json/write-str result))
    (log/info "Finished save aggregation" id)))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (condp = (first args)
    "sample-crossref-continuous" (run-sample-crossref-continuous)
    "sample-crossref-once" (run-sample-crossref-once)
    "sample-datacite-continuous" (run-sample-datacite-continuous)
    "sample-datacite-once" (run-sample-datacite-once)

    "observation-continuous" (run-observation-continuous)
    "extraction-continuous" (run-extraction-continuous)

    ; Aggregation is a once-off activity.
    "aggregation-once" (run-aggregation-once)

    ; Rescanning involves re-reading the output of the previous stage.
    "rescan-observation" (requeue (:visitor-sample-topic env) :sample)
    "rescan-extraction" (requeue (:visitor-observation-topic env) :observation)))


