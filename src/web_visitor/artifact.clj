(ns web-visitor.artifact
  (:require [clojure.tools.logging :as log]))

(defn gt-zero [x] (> (or x 0) 0))

(defn union-domains-from-source
  "All domains that were arrived from a particular source, including http destination, browser destination and resource URL.
  Source-id is one of :crossref-api or :datacite-api."
  [input source-id]
  (->> input
   :union-domains
   (filter #(-> % second :source-counts source-id gt-zero))
   (map first)
   (map name)
   (map clojure.string/lower-case)
   set))

(defn destination-domains-from-source
  "Destination domains that were arrived from a particular source. Source-id is one of :crossref-api or :datacite-api."
  [input source-id]
  (clojure.set/union
    (->> input
     :http-domains
     (filter #(-> % second :source-counts source-id gt-zero))
     (map first)
     (map name)
     (map clojure.string/lower-case)
     set)
    (->> input
     :browser-domains
     (filter #(-> % second :source-counts source-id gt-zero))
     (map first)
     (map name)
     (map clojure.string/lower-case)
     set)))


(defn to-text
  [sequence]
  (clojure.string/join "\n" sequence))

(defn aggregation->crossref-full-domain-list
  [input]
  (-> input
       (destination-domains-from-source :crossref-api)
       sort
       to-text))

(defn aggregation->datacite-full-domain-list
  [input]
  (-> input
       (destination-domains-from-source :datacite-api)
       sort
       to-text))

(defn aggregation->full-domain-list
  [input]
  (->> input
       :union-domains
       keys
       sort
       (map name)
       to-text))

(defn aggregation->intersection-domain-list
  [input]
  ; Take union domains (i.e. http destination, browser destination and resource URL) as we're trying to find the most commonality.
  (let [crossref-domains (union-domains-from-source input :crossref-api)
        datacite-domains (union-domains-from-source input :datacite-api)
        intersection (clojure.set/intersection crossref-domains datacite-domains)]
    (log/info "Intersection found crossref:" (count crossref-domains) "datacite:" (count datacite-domains) "intersection:" (count intersection))
    (->> intersection
         sort
         to-text)))

(defn all-prefix-roundtrip-domains
  "All domains that made some kind of Prefix roundtrip."
  [input]
  (set
    (map #(-> % first name)
      (filter
        (fn [[k v]]
          (or (-> v :best-roundtrip-counts :http gt-zero)
              (-> v :best-roundtrip-counts :browser gt-zero)))
        (:prefix-roundtrip input)))))

(defn aggregation->crossref-prefix-roundtrip-domain-list
  [input]
  (let [prefix-roundtrip-domains (all-prefix-roundtrip-domains input)
        crossref-domains (destination-domains-from-source input :crossref-api)
        result (clojure.set/intersection prefix-roundtrip-domains crossref-domains)]
    (log/info "Crossref prefix roundtrip. Prefix roundtrip:" (count prefix-roundtrip-domains) "Crossref:" (count crossref-domains) "Intersection:" (count result))
    (to-text (sort result))))

(defn aggregation->crossref-prefix-roundtrip-fail-domain-list
  [input]
  (let [prefix-roundtrip-domains (all-prefix-roundtrip-domains input)
        crossref-domains (destination-domains-from-source input :crossref-api)
        result (clojure.set/difference crossref-domains prefix-roundtrip-domains)]
    (log/info "Crossref prefix roundtrip failures. Prefix roundtrip:" (count prefix-roundtrip-domains) "Crossref:" (count crossref-domains) "Difference:" (count result))
    (to-text (sort result))))

(defn aggregation->datacite-prefix-roundtrip-domain-list
  [input]
  (let [prefix-roundtrip-domains (all-prefix-roundtrip-domains input)
        datacite-domains (destination-domains-from-source input :datacite-api)
        result (clojure.set/intersection prefix-roundtrip-domains datacite-domains)]
    (log/info "Crossref prefix roundtrip. Prefix roundtrip:" (count prefix-roundtrip-domains) "Crossref:" (count datacite-domains) "Intersection:" (count result))
    (to-text (sort result))))

(defn aggregation->datacite-prefix-roundtrip-fail-domain-list
  [input]
  (let [prefix-roundtrip-domains (all-prefix-roundtrip-domains input)
        datacite-domains (destination-domains-from-source input :datacite-api)
        result (clojure.set/difference datacite-domains prefix-roundtrip-domains)]
    (log/info "Crossref prefix roundtrip failures. Prefix roundtrip:" (count prefix-roundtrip-domains) "Crossref:" (count datacite-domains) "Difference:" (count result))
    (to-text (sort result))))

(defn aggregation->prefix-roundtrip-domain-list
  [input]
  (-> input all-prefix-roundtrip-domains to-text))


(defn aggregation->prefix-roundtrip-fail-domain-list
  [input]
  (let [prefix-roundtrip-domains (all-prefix-roundtrip-domains input)
        datacite-domains (destination-domains-from-source input :datacite-api)
        crossref-domains (destination-domains-from-source input :crossref-api)
        domains (clojure.set/union datacite-domains crossref-domains)
        result (clojure.set/difference domains prefix-roundtrip-domains)]
    (log/info "All prefix roundtrip failures. Prefix roundtrip:" (count prefix-roundtrip-domains) "Domains:" (count domains) "Difference:" (count result))
    (to-text (sort result))))


(defn all-doi-roundtrip-domains
  "All domains that made some kind of DOI roundtrip."
  [input]
  (set
    (map #(-> % first name)
      (filter
        (fn [[k v]]
          (or (-> v :best-roundtrip-counts :http gt-zero)
              (-> v :best-roundtrip-counts :browser gt-zero)))
        (:doi-roundtrip input)))))

(defn aggregation->crossref-doi-roundtrip-domain-list
  [input]
  (let [doi-roundtrip-domains (all-doi-roundtrip-domains input)
        crossref-domains (destination-domains-from-source input :crossref-api)
        result (clojure.set/intersection doi-roundtrip-domains crossref-domains)]
    (log/info "Crossref doi roundtrip. Prefix roundtrip:" (count doi-roundtrip-domains) "Crossref:" (count crossref-domains) "Intersection:" (count result))
    (to-text (sort result))))

(defn aggregation->crossref-doi-roundtrip-fail-domain-list
  [input]
  (let [doi-roundtrip-domains (all-doi-roundtrip-domains input)
        crossref-domains (destination-domains-from-source input :crossref-api)
        result (clojure.set/difference crossref-domains doi-roundtrip-domains)]
    (log/info "Crossref doi roundtrip failures. Prefix roundtrip:" (count doi-roundtrip-domains) "Crossref:" (count crossref-domains) "Difference:" (count result))
    (to-text (sort result))))

(defn aggregation->datacite-doi-roundtrip-domain-list
  [input]
  (let [doi-roundtrip-domains (all-doi-roundtrip-domains input)
        datacite-domains (destination-domains-from-source input :datacite-api)
        result (clojure.set/intersection doi-roundtrip-domains datacite-domains)]
    (log/info "Crossref doi roundtrip. Prefix roundtrip:" (count doi-roundtrip-domains) "Crossref:" (count datacite-domains) "Intersection:" (count result))
    (to-text (sort result))))

(defn aggregation->datacite-doi-roundtrip-fail-domain-list
  [input]
  (let [doi-roundtrip-domains (all-doi-roundtrip-domains input)
        datacite-domains (destination-domains-from-source input :datacite-api)
        result (clojure.set/difference datacite-domains doi-roundtrip-domains)]
    (log/info "Crossref doi roundtrip failures. doi roundtrip:" (count doi-roundtrip-domains) "Crossref:" (count datacite-domains) "Difference:" (count result))
    (to-text (sort result))))

(defn aggregation->doi-roundtrip-domain-list
  [input]
  (-> input all-doi-roundtrip-domains to-text))


(defn aggregation->doi-roundtrip-fail-domain-list
  [input]
  (let [doi-roundtrip-domains (all-doi-roundtrip-domains input)
        datacite-domains (destination-domains-from-source input :datacite-api)
        crossref-domains (destination-domains-from-source input :crossref-api)
        domains (clojure.set/union datacite-domains crossref-domains)
        result (clojure.set/difference domains doi-roundtrip-domains)]
    (log/info "All doi roundtrip failures. doi roundtrip:" (count doi-roundtrip-domains) "Domains:" (count domains) "Difference:" (count result))
    (to-text (sort result))))

(def artifact-names
  "Artifact name, function pairs."
  [["crossref-full-domain-list" aggregation->crossref-full-domain-list ]
   ["datacite-full-domain-list" aggregation->datacite-full-domain-list ]
   ["full-domain-list" aggregation->full-domain-list ]
   ["intersection-domain-list" aggregation->intersection-domain-list]
   ["crossref-prefix-roundtrip-domain-list" aggregation->crossref-prefix-roundtrip-domain-list ]
   ["crossref-prefix-roundtrip-fail-domain-list" aggregation->crossref-prefix-roundtrip-fail-domain-list ]
   ["datacite-prefix-roundtrip-domain-list" aggregation->datacite-prefix-roundtrip-domain-list ]
   ["datacite-prefix-roundtrip-fail-domain-list" aggregation->datacite-prefix-roundtrip-fail-domain-list ]
   ["prefix-roundtrip-domain-list" aggregation->prefix-roundtrip-domain-list ]
   ["prefix-roundtrip-fail-domain-list" aggregation->prefix-roundtrip-fail-domain-list ]
   ["crossref-doi-roundtrip-domain-list" aggregation->crossref-doi-roundtrip-domain-list ]
   ["crossref-doi-roundtrip-fail-domain-list" aggregation->crossref-doi-roundtrip-fail-domain-list ]
   ["datacite-doi-roundtrip-domain-list" aggregation->datacite-doi-roundtrip-domain-list ]
   ["datacite-doi-roundtrip-fail-domain-list" aggregation->datacite-doi-roundtrip-fail-domain-list ]
   ["doi-roundtrip-domain-list" aggregation->doi-roundtrip-domain-list ]
   ["doi-roundtrip-fail-domain-list" aggregation->doi-roundtrip-fail-domain-list]])

