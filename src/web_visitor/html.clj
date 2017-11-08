(ns web-visitor.html
  (:require [clojure.tools.logging :as log]
            [crossref.util.doi :as cr-doi]
            [clojure.string :as string])
  (:import [org.jsoup Jsoup]))

(def interested-meta-tag-names
  "Case insensitive."
  #{"citation_doi"
    "dc.identifier"
    "dc.identifier.doi"
    "prism.doi"})

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

(defn try-get-domain
  [url-str]
  (try
    (.getHost (java.net.URI. url-str))
    (catch Exception _ nil)))

(defn summary-from-trace
  "For HTML text and DOI return those meta tags that matched the DOI and those that contained a conflicting DOI."
  [doi trace]
  (let [doi (cr-doi/normalise-doi doi)
        prefix (cr-doi/get-prefix doi)

        body (or (-> trace last :body) "")
        url (-> trace last :url)
        urls (map :url trace)
        lowercase-body (string/lower-case body)

        tags-present (identifier-doi-tags-present body)
        
        ; Split into those tags that had the DOI and those that had something else.
        ; As these are all regarded as well-formed, these must be competing DOIs!
        correct-doi-meta-tags (map first (filter #(= doi (second %)) tags-present))
        incorrect-doi-meta-tags (map first (remove #(= doi (second %)) tags-present))

        correct-prefix-meta-tags (map first (filter #(= prefix (cr-doi/get-prefix (second %))) tags-present))
        incorrect-prefix-meta-tags (map first (remove #(= prefix (cr-doi/get-prefix (second %))) tags-present))
        text-doi-matches (string/includes? lowercase-body (string/lower-case (cr-doi/non-url-doi doi)))
        text-prefix-matches (string/includes? body prefix)]

; As these results are an output of the program, they are documented in the README.md
{:domain (try-get-domain url)
 :domains (distinct (keep try-get-domain urls))
 :redirects-count (count trace)
 :correct-doi-meta-tags correct-doi-meta-tags
 :incorrect-doi-meta-tags incorrect-doi-meta-tags
 :doi-roundtrip (boolean (and (not-empty correct-prefix-meta-tags) (empty? incorrect-doi-meta-tags)))
 :correct-prefix-meta-tags correct-prefix-meta-tags
 :incorrect-prefix-meta-tags incorrect-prefix-meta-tags
 :prefix-roundtrip (boolean (and (not-empty correct-prefix-meta-tags) (empty? incorrect-prefix-meta-tags)))
 :text-doi-matches text-doi-matches
 :text-prefix-matches text-prefix-matches}))
