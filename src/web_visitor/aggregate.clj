(ns web-visitor.aggregate
  (:require [crossref.util.doi :as cr-doi]))

(def unknown "UNKNOWN")

; Increment from nil.
(defn i [arg] (inc (or arg 0)))

(defn clean-path-nils
  "Remove nils from path so we create a valid JSON structure."
  [p]
  (map #(or % unknown) p))


(defn increment-many*
  "Given a set of items, increment the path in the acc structure by one for each item, with the given prefix"
  [acc path-prefix items path-suffix]
  (if (empty? items)
    acc
    (increment-many*
      (update-in acc
                 (concat path-prefix [(first items)] path-suffix)
                 i)
      path-prefix
      (rest items)
      path-suffix)))

(defn increment-many
  [acc path-prefix items path-suffix]
  (increment-many*
    acc
    (clean-path-nils path-prefix)
    (clean-path-nils items)
    (clean-path-nils path-suffix)))


(defn aggregate
  [acc extraction]
  (let [prefix (cr-doi/get-prefix (:doi extraction))
        source (-> extraction :observation-input :sample-input :source)
        all-domains (:all-domains extraction)]
    (->
      acc
      (update-in [:source source] i)

      (update-in [:prefixes prefix :total] i)

      (update-in [:prefixes prefix :source source :total] i)
      
      (increment-many [:domains]
                      all-domains
                      [:total])

      (increment-many [:domains]
                      all-domains
                      [:sources source :total])

      (increment-many [:domains]
                      all-domains
                      [:prefixes prefix :total])

      (increment-many [:domains]
                      all-domains
                      [:prefixes prefix :browser-http-unambiguous (-> extraction :http :meta-unambiguous)])

      (increment-many [:domains]
                      all-domains
                      [:prefixes prefix :browser-meta-unambiguous (-> extraction :browser :meta-unambiguous)])

      (increment-many [:domains]
                      all-domains
                      [:status :http (-> extraction :observation-input :http-status)])

      (increment-many [:domains]
                      all-domains
                      [:status :browser (-> extraction :observation-input :browser-status)]))))
