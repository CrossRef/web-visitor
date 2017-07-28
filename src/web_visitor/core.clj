(ns web-visitor.core
  (:require [web-visitor.sample :as sample]
            [clojure.tools.logging :as log]
            [config.core :refer [env]])
  (:import [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord])
  (:gen-class))

(def kafka-producer
  (delay
    (KafkaProducer.
      {"bootstrap.servers" (:global-kafka-bootstrap-servers env)
       "acks" "all"
       "retries" (int 5)
       "key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
       "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"})))

(defn run-one-sample
  []
  "Collect a random sample of DOIs, weighted by publisher member, send to Kafka topic."
  (log/info "Running a sample batch.")
  (let [dois (sample/sample-all)]
    (doseq [doi dois]
      (.send @kafka-producer
        (ProducerRecord. (:visitor-doi-sample-topic env) doi)))))

(defn run-sample
  []
  (loop []
    (run-one-sample)
    (recur)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (condp = (first args)
    "sample" (run-sample)
    "one-sample" (run-one-sample)))


