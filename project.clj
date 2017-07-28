(defproject web-visitor "0.1.0-SNAPSHOT"
  :description "Web Visitor"
  :license {:name "The MIT License (MIT)"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.webfolder/cdp4j "1.2.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.apache.logging.log4j/log4j-core "2.6.2"]
                 [org.slf4j/slf4j-simple "1.7.21"]
                 [robert/bruce "0.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [http-kit "2.1.18"]
                 [yogthos/config "0.8"]
                 [org.apache.kafka/kafka-clients "0.10.2.0"]]
  :main ^:skip-aot web-visitor.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
