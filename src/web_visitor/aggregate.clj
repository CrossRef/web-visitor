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

(defn update-in-safe
  [m ks f]
  (update-in m (clean-path-nils ks) f))


(defn aggregate
  [acc e]
  (let [prefix (cr-doi/get-prefix (:doi e))
        source (-> e :observation-input :sample-input :source)
        ; all-domains (:all-domains e)

        resource-domain (-> e :resource :domain)
        http-domain (-> e :http :domain)
        browser-domain (-> e :browser :domain)
        ]
    (->
      acc

      ;; Counts.
      (update-in-safe [:samples
                       :total] i)

      (update-in-safe [:samples
                         :source-counts
                           source] i)

      ;; Prefix

      ; Source count per prefix.
      (update-in-safe [:prefixes
                         prefix
                           :source-counts
                             source] i)

      ; Total sample count per prefix.
      (update-in-safe [:prefixes
                         prefix
                           :total] i)

      ; DOI Roundtrip breakdown per prefix.
      (update-in-safe [:prefixes
                         prefix
                           :best-prefix-roundtrip-counts
                           (-> e :best :prefix-roundtrip)] i)

      ; Prefix Roundtrip breakdown per prefix.
      (update-in-safe [:prefixes
                         prefix
                           :best-doi-roundtrip-counts
                           (-> e :best :doi-roundtrip)] i)

      ;; Resource domain

      ; Source count per resource domain.
      (update-in-safe [:resource-domains
                         resource-domain
                           :source-counts
                             source] i)

      ; Total samples per resource domain.
      (update-in-safe [:resource-domains
                         resource-domain
                           :total] i)

      ; Total prefixes that mapped to resource domain.
      (update-in-safe [:resource-domains
                         resource-domain
                           :prefix-counts
                             prefix] i)

      ;; HTTP Domain

      ; Source count per HTTP domain.
      (update-in-safe [:http-domains
                         http-domain
                           :source-counts
                             source] i)

      ; Total samples per HTTP domain.
      (update-in-safe [:http-domains
                         http-domain
                           :total] i)

      ; Total prefixes that mapped to HTTP domain.
      (update-in-safe [:http-domains
                         http-domain
                           :prefix-counts
                             prefix] i)

      ;; Browser Domain

      ; Source count per Browser domain.
      (update-in-safe [:browser-domains
                         browser-domain
                           :source-counts
                             source] i)

      ; Total samples per Browser domain.
      (update-in-safe [:browser-domains
                         browser-domain
                           :total] i)

      ; Total prefixes that mapped to Browser domain.
      (update-in-safe [:browser-domains
                         browser-domain
                           :prefix-counts
                             prefix] i)

      ;; Union of HTTP, Browser and Resource domains.

      (update-in-safe [:union-domains
                         resource-domain
                           :source-counts
                             source] i)

      (update-in-safe [:union-domains
                         resource-domain
                           :total] i)

      (update-in-safe [:union-domains
                         resource-domain
                           :prefix-counts
                             prefix] i)

      (update-in-safe [:union-domains
                         http-domain
                           :source-counts
                             source] i)

      (update-in-safe [:union-domains
                         http-domain
                           :total] i)

      (update-in-safe [:union-domains
                         http-domain
                           :prefix-counts
                             prefix] i)

      (update-in-safe [:union-domains
                         browser-domain
                           :source-counts
                             source] i)

      (update-in-safe [:union-domains
                         browser-domain
                           :total] i)

      (update-in-safe [:union-domains
                         browser-domain
                           :prefix-counts
                             prefix] i)

      ;; DOI Roundtrip

      ; Count of best roundtrip methods for whole domain.
      (update-in-safe [:doi-roundtrip

                         ; The HTTP and Browser domains might in theory be different.
                         ; When we're recording the best method for accessing a domain therefore, 
                         ; we need to choose which domain we're recording it against.
                         (condp = (-> e :best :doi-roundtrip)
                           "http" http-domain
                           "browser" browser-domain
                           ; If there isn't a way of roundtripping,
                           ; record against the most reliable domain we have.
                           "NULL" (or http-domain browser-domain resource-domain "UNKNOWN")
                           "UNKNOWN")

                           :best-roundtrip-counts
                             ; This will be one of 'http', 'browser' or NULL
                             (-> e :best :doi-roundtrip)] i)

      ; Count of best roundtrip methods for this domain by each prefix sampled.
      (update-in-safe [:doi-roundtrip
                         
                         (condp = (-> e :best :doi-roundtrip)
                           "http" http-domain
                           "browser" browser-domain
                           "NULL" (or http-domain browser-domain resource-domain "UNKNOWN")
                           "UNKNOWN")

                           :prefixes
                             prefix
                               (-> e :best :doi-roundtrip)] i)

      ;; DOI Prefix Roundtrip

      ; Count of best roundtrip methods for whole domain.
      (update-in-safe [:prefix-roundtrip

                         (condp = (-> e :best :prefix-roundtrip)
                           "http" http-domain
                           "browser" browser-domain
                           "NULL" (or http-domain browser-domain resource-domain "UNKNOWN")
                           "UNKNOWN")

                           :best-roundtrip-counts
                             ; This will be one of 'http', 'browser' or NULL
                             (-> e :best :prefix-roundtrip)] i)

      ; Count of best roundtrip methods for this domain by each prefix sampled.
      (update-in-safe [:prefix-roundtrip
                         (condp = (-> e :best :prefix-roundtrip)
                           "http" http-domain
                           "browser" browser-domain
                           "NULL" (or http-domain browser-domain resource-domain "UNKNOWN")
                           "UNKNOWN")

                           :prefixes
                             prefix
                               (-> e :best :prefix-roundtrip)] i))))


