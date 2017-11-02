(ns web-visitor.sample-datacite
  "Collect a representative sample of DOIs per prefix.
   As the DataCite API doesn't have random samples, take a randomly selected page."
  (:require [org.httpkit.client :as http]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [robert.bruce :refer [try-try-again]])
  (:gen-class))

(defn all-prefixes-throwing
  "Return a seq of [prefix, num-dois]."
  []
    (let [response (http/get "https://search.datacite.org/list/generic?&facet.field=prefix"
                             {:as :text})

          lines (-> response
                     deref
                     :body
                     (.split "\n"))

          result (->> lines
                     (map (partial re-find #"^(10\.\d+); (\d+);$"))
                     (map (fn [[_ prefix cnt]]
                            [prefix (Integer/parseInt cnt)])))]
      result))

(defn all-prefixes
  []
  (try-try-again
    {:sleep 5000 :tries 2}
    #(all-prefixes-throwing)))


; We're limited by the REST API to 100.
(def max-sample 100)

; Always take at least this many samples.
(def min-sample 10)

(defn num-samples-per-prefix
  "Transform seq of [prefix num-dois] to [prefix random-offset num-samples]"
  [prefixes-and-counts]
  (let [max-value (apply max (map second prefixes-and-counts))
        scale-factor (/ max-sample max-value)]
    (map (fn [[prefix num-dois]]
           (let [num-samples (max min-sample 
                                  (int (* scale-factor num-dois)))
                 ; Might be fewer DOIs than min-sample.
                 random-offset (max 0 (int (Math/floor (* (Math/random) (- num-dois num-samples)))))]
            (println prefix num-dois random-offset num-samples)
             [prefix random-offset num-samples]))
          prefixes-and-counts)))

(defn doi-sample-for-prefix-throwing
  "Return a seq of a sample of DOIs for the prefix."
  [[prefix random-offset num-samples]]
  (log/info "Sample " num-samples " DOIs for prefix" prefix)
  (let [response @(http/get "https://api.datacite.org/works"
                           {:query-params {:rows num-samples
                                           :offset random-offset
                                           :query (str "prefix:" prefix)}
                            :as :text})
        body (json/read-str (:body response) :key-fn keyword)
        works (-> body :data)
        dois (map :id works)]
    dois))

(defn doi-sample-for-prefix
  [input]
  (try-try-again
    {:sleep 5000 :tries 2}
    #(doi-sample-for-prefix-throwing input)))


(defn sample-all
  "Return a randomly shuffled sample of DOIs for all prefixes."
  []
  (let [; Order doesn't matter, so sort by prefix ID so we know how far we are through the job.
        prefixes-and-counts (sort-by first (all-prefixes))
        prefixes-and-samples (num-samples-per-prefix prefixes-and-counts)
        dois (mapcat doi-sample-for-prefix prefixes-and-samples)
        shuffled (shuffle dois)]
    shuffled))

