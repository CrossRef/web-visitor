(defproject web-visitor "0.1.0-SNAPSHOT"
  :description "Web Visitor"
  :license {:name "The MIT License (MIT)"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [event-data-common "0.1.38"]
                 [clj-time "0.12.2"]
                 [enlive "1.1.6"]
                 [crossref-util "0.1.14"]
                 [io.webfolder/cdp4j "2.1.1"] ; 1.2.0
                 [org.clojure/tools.logging "0.3.1"]
                 [com.fzakaria/slf4j-timbre "0.3.7"]
                 [org.slf4j/slf4j-api "1.7.14"]
                 [com.taoensso/timbre "4.10.0"]

                 [robert/bruce "0.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [http-kit "2.1.18"]
                 [yogthos/config "0.8"]
                 [org.apache.kafka/kafka-clients "0.10.2.0"]]

  ; Exclude so we can use Timbre.
  ; Without this exclusion there's a SL4J conflict which means we can't configure
  ; the logging behaviour of cdp4j and it's VERY chatty.
  ; If Timbre makes it into common, we won't need this.
  :exclusions [org.slf4j/slf4j-simple]

  :main ^:skip-aot web-visitor.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
