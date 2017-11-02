(ns web-visitor.html
  (:require [clojure.tools.logging :as log]
            [crossref.util.doi :as cr-doi])
  (:import [org.jsoup Jsoup]))

(def interested-meta-tag-names
  "Case insensitive."
  #{"citation_doi"
    "dc.identifier"
    "dc.identifier.doi"
    "prism.doi"})

    ["meta[name=DC.Source]" "content"]


(defn identifier-doi-tags-present
  "Return seq of [meta-tag-name, content] for all types of meta tag we're interested in."
  [html-text]
  (try
    (when html-text
      (let [document (Jsoup/parse html-text)

            ; Get specific attribute values from named elements.
            ; There may be more than one per type!
            ; Only take those that have well-formed DOIs to exclude competing identifiers.
            attr-values (mapcat (fn [meta-tag-name]
                                  (->>
                                    (.select document (str "meta[name=" meta-tag-name "]"))
                                    (map #(vector meta-tag-name (.attr % "content")))
                                    (keep (fn [[nom doi]]
                                            (when (cr-doi/well-formed doi)
                                              [nom (cr-doi/normalise-doi doi)])))))
                                interested-meta-tag-names)]

        attr-values))
    ; We're getting text from anywhere. Anything could happen.
    (catch Exception ex (do
      (log/warn "Error parsing HTML for DOI.")
      (.printStackTrace ex)
      nil))))

(defn create-doi-summary
  "For HTML text and DOI return those meta tags that matched the DOI and those that contained a conflicting DOI."
  [un-normalised-doi html-text]
  (let [doi (cr-doi/normalise-doi un-normalised-doi)
        tags-present (identifier-doi-tags-present html-text)
        ; Split into those tags that had the DOI and those that had something else.
        ; As these are all regarded as well-formed, these must be competing DOIs!
        matches (map first (filter #(= doi (second %)) tags-present))
        conflicts (map first (remove #(= doi (second %)) tags-present))]

  {:matches matches
   :conflicts conflicts}))

