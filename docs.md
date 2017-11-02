# Visitor

DOI Web Visitor takes a sample of DOIs and makes observations about them and the sites they resolve to. It works in three stages:

### 1 - Sampling DOIs

Take a sample of DOIs from the Crossref and DataCite APIs, in proportion to membership. This can run as a one-off batch or continuously. These are supplied as a stream of:

 1. `id` - A guid for deduplication.
 2. `doi` - The DOI.
 3. `source` - The source of the sample. "crossref-api" or "datacite-api".

ID is used for deduplication further down the pipeline. The ID for the input isn't the DOI, to ensure that we can re-sample a DOI twice if we want.

### 2 - Observation

Visit DOIs and make observations. There are a number of observation and every observation type is made for each DOI in turn. All observation are made in one batch per DOI. Fields in an observation:

 1. `id`
 1. `sample-input` - Nested sample input object.
 2. `timestamp` - ISO8601 timestamp that observation was made.
 3. `resource-url` - The Resource URL for a DOI, as returned by the Handle system.
 4. `http-url` - The Destination URL arrived at by retrieving the `resource-url` and following any HTTP redirects.
 5. `http-status` - The HTTP status code that accompanies the `http-url`
 5. `browser-url` - The Destination URL arrived at by visiting `resource-url` with a headless browser and following redirects as a browser would.
 6. `browser-status` - The HTTP status code that accompanies the `browser-url`.
 6. `http-trace` - A sequence of objects with {url status body} that represent the URL, status code and body text. Each entry represents a retrieval: f there are redirects there will be more than one item in the list.
 7. `browser-trace` - The sequence of objects, with the same format as `http-trace`, for the headless web browser. Because of the way the data is polled, only the last item has any body text. The body is taken from the live DOM in the browser, and so may vary from the HTML that is served.

### 3 - Extraction

Extraction happens distinct from observation so that they can be re-created if wanted. All of the data for the analysis comes from the Observation object.

 1. `id` - A guid for deduplication.
 1. `observation` - Nested Obseration object.
 2. `input-doi` - The original DOI, normalized to lower case.
 3. `input-doi-prefix` - Prefix of the input DOI.
 4. `http-doi` - The DOI derived from the DC.Identifier metdata in the bo`dy text of the input retrieved via HTTP. Can be null.
 5. `http-doi-prefix` - The Prefix of the http-doi.
 6. `browser-doi` - The DOI derived from the DC.Identifier metdata in the body text of the input returned via Browser.
 7. `browser-doi-prefix` - The Prefix fo the browser-doi.
 8. `resource-url-domain` - The domain name of the Resource URL.
 9. `http-url-domain` - The domain of http-url
 10. `browser-url-domain` - The domain of the browser-url
 11. `resource-url-scheme` - One of "http" "https" or null.
 12. `http-url-scheme` - One of "http" "https" or null.
 14. `browser-url-scheme` - One of "http" "https" or null.

### 4 - Analysis

Analysis happens on fields provided in the Extraction stage. Fields:

 1. `id` - A guid for deduplication.
 1. `extraction` - Nested Extraction object and all that that entails.
 2. `doi-in-http` - Is the input DOI available from HTTP-fetched document?
 3. `doi-in-browser` - Is the input DOI available from the Browser-fetched document?
 4. `doi-prefix-in-http` - Is the input DOI prefix available from the HTTP-fetched document? i.e. could be different DOI but same prefix.
 5. `doi-prefix-in-browser` - Is the DOI prefix available form the Browser-fetched document?
 6. `minimal-doi-method` - What's the easiest way to get the DOI? Can be 'http' in preference or 'browser', or null if not available.
 7. `http-cross-domain` - Is the domain of the site as fetched with HTTP different to the resource URL?
 8. `browser-cross-domain` - Is the domain of the site as fetched with Browser browser different the the resource URL?
 9. `http-doi-roundtrip` - Using HTTP, did the DOI lead to a page that led back to the same DOI?
 10. `browser-doi-roundtrip` - Using Browser, did the DOI lead to a page that led back to the same DOI?
 11. `http-doi-prefix-roundtrip` - Using HTTP, did the DOI lead to a page that led back to the same DOI prefix?
 12. `browser-doi-prefix-roundtrip` - Using Browser, did the DOI lead to a page that led back to the same DOI prefix?
 13. `minimal-doi-rountrip` - The minimal method for a successful DOI roundtrip. One of "http", "browser" or nil.
 14. `minimal-doi-prefix-rountrip` - The minimal method for a successful DOI prefix roundtrip. One of "http", "browser" or nil.

### 5 - Aggregation

Up to this point every Sample, Observation, Extraction and Analysis is represented as a JSON document keyed by the original ID. Analysis objects are inserted into ElasticSearch. The following queries are available, generally as facet counts.

#### Per DOI Prefix:

 1. - All Resource URL domains in proportion.
 2. - All HTTP URL domains in proportion.
 3. - All Browser URL domains in proportion.
 4. - All Resource URL schemes in proportion.
 5. - All HTTP URL schemes in proportion.
 6. - All Browser URL schemes in proportion.
 7. - Best method to find correct DOI in proportion.

#### Per domain in Resource position:

1. - DOI Prefixes in proportion.

#### Per domain in HTTP position:

1. - DOI Prefixes in proportion.

#### Per domain in Browser position:

1. - DOI Prefixes in proportion.

#### Per domains in any position:

1. - Best method to find correct DOI in proportion.

### 6 - Snapshot

The results can be exported as a snapshot for use by Agents:

all-page-domains - A list of all domains found in any position.

domain - whitelist 

A whitelist of which domains are allowed to make assertions about which DOI prefixes. 

The structure is:

    {"domain.com": {"10.5555": {"preferred-method": "http", proportion: 0.8}
                    "10.6666": {"preferred-method": "browser", proportion: 0.2}}}

An entry makes the whitelist if there are a number of instances where a given DOI maps to that domain, and the domain refers back to the DOI (i.e. a roundtrip).

