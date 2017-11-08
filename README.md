# Web Visitor

Visit DOIs, following them to their landing page. Make observations about how the DOIs and their landing pages relate. Produces a number of Artifacts for Event Data. For explantion of precisely what data is collected and how, see [docs.md](docs.md).

Runs with Docker. Has a few dependencies:

 - Apache Kafka for queuing and balancing.
 - ElasticSearch for query and aggregation.
 - AWS S3 for permanent storage.
 - Headless Chrome for visiting pages.

Can be run purely with the docker-compose file, which wires up everything except S3 configuration. All data stored locally can be treated as ephemeral. All permanent data is stored in S3.

## To Run

This is designed for continual operation, with occasional scheduled aggregations. The parts that run continually:

 - Sample from Crossref
 - Sample from DataCite
 - Observation
 - Extraction

Parts that run as one-offs

 - Aggregation

Tp run, assuming use of the Docker Compose file:

Start things up:

    docker-compose start kafka

NB it's important to do this first or the topics will be auto-created with the wrong number of partitions!

    ./create-topics.sh

REPL:

    docker-compose run repl lein repl

Continous sample from Crossref and DataCite respectively

    docker-compose run sample_crossrefcite

    docker-compose run sample_datacite

Continuous observation. There are three containers set up to run in parallel, for individual logging.

    docker-compose run observation1
    docker-compose run observation2
    docker-compose run observation3
    docker-compose run observation4
    docker-compose run observation5

Alternatively, to run in the background:

  sudo docker-compose start observation1
  ...

Don't just start everything unless you want the sampling to run continuously.

Continuous extraction

    docker-compose run extraction

One off aggregation:

    docker-compose run aggregation


## Config

  - VISITOR_S3_KEY
  - VISITOR_S3_SECRET
  - VISITOR_S3_REGION_NAME
  - VISITOR_S3_BUCKET_NAME
  - VISITOR_SAMPLE_TOPIC - Kafka topic for DOI samples.
  - VISITOR_OBSERVATION_TOPIC - Kafka topic for observations.
  - VISITOR_EXTRACTION_TOPIC - Kafka topic for extraction.
  - VISITOR_ANALYSIS_TOPIC - Kafka topic for analysis.
  - VISITOR_CHROME_HOST 
  - VISITOR_CHROME_PORT 
  - GLOBAL_KAFKA_BOOTSTRAP_SERVERS - Kafka bootstrap servers
  - VISITOR_PORT - port for serving up API

If running from Docker Compose file, you need to supply the following in a .env file:

 - VISITOR_S3_KEY
 - VISITOR_S3_SECRET
 - VISITOR_S3_REGION_NAME
 - VISITOR_S3_BUCKET_NAME

A Kafka topic should be created and the name should be supplied as VISITOR_DOI_SAMPLE_TOPIC. It should have a largeish number of partitions, e.g. at least 100, to allow for parallel processing.

## Discussion

### Extraction

Extraction looks at each snapshot (i.e. a sample that started with a DOI).

Round-trips are recorded. A roundtrip happens when we visit a DOI and follow it to its landing page. We then inspect the meta tags to see if the page indicates that it has a DOI, and if so, which DOI it claims to have. The result is one of the following:

 - `correct` - The correct DOI was indicated.
 - `incorrect` - The incorrect DOI was indicated.
 - `none` - No DOI was indicated.
 - `conflict` - Both the correct and an incorrect DOI was mentioned.

Roundtrips are conducted on both the HTTP-retrieved HTML and the browser-retrieved HTML. One check is made for the DOI and another simply for the correct prefix.

The following are produced:

 - `prefix` - Prefix of the DOI.
 - `resource` - Data extracted from Resource URL:
   - `domain` - Domain name.
 - `http` - Data extracted from HTTP trace observation:
   - `domain` - Domain name of destination.
   - `domains` - Distinct domain names of any redirects.
   - `redirects-count` - Number of redirects. 
   - `correct-doi-meta-tags` - Meta tags that match the DOI correctly.
   - `incorrect-doi-meta-tags` - Meta tags that have a different DOI.
   - `doi-roundtrip` - Is a roundtrip possible? True iff there's a correct DOI and no incorrect ones.
   - `correct-prefix-meta-tags` - Meta tags that have a DOI matching the prefix correctly.
   - `incorrect-prefix-meta-tags` - Meta tags that have a DOI that doesn't match the prefix correctly.
   - `prefix-roundtrip` - Is a roundtrip possible for the DOI prefix? True iff there's a DOI with the correct prefix.
   - `text-doi-matches` - Is the DOI mentioned somewhere in the HTML text?
   - `text-prefix-matches` - Is the DOI prefix mentioned somewhere in the HTML text?
 - `browser` - Data extracted from the Browser trace observation. All fields as per `http`.
 - `union` - Data from the union of `resource`, `http` and `browser`
   - `distinct-domains` - Distinct union of `resource.domain`, `http.domains`, `browser.domains`
 - `best` - When all's said and done, what's the best method for doing a reliable, unambiguous roundtrip?
     - `doi-roundtrip` - One of `http`, `browser` or null
     - `prefix-roundtrip` - One of `http`, `browser` or null

### Aggregation

Aggregation based on the extraction. Split into three categories: Prefix oriented, Domain oriented, and DOI-Roundtrip oriented. Within each category there are other dimensions.



#### prefixes
Observations made about DOI Prefixes.

- `«prefix»` - The prefix in question
    - `total` : «count» - Total number of samples for this prefix.
    - `source-counts` - The sources and counts from which we arrived at this Prefix. This should be either DataCite or Crossref, but the structure allows for more than one.
        - `«source»` : «count» - The source (one of "crossref-api" or "datacite-api") and the number of samples. e.g. "this prefix obtained from the Crossref API from 5 samples".


#### resource-domains
Observations made about Domains found in **Resource URL**.

- `«domain»` - The Domain in question.
    - `total` : «count» - Total number of resource-url samples for this domain.
    - `source-counts` - Sources and counts from which samples were taken to arrive at this domain.
        - `«source»` : «count» - The source (one of "crossref-api" or "datacite-api") and the number of samples. e.g. "this Resource URL Domain was arrived at from the Crossref API from 5 samples".
    - `prefix-counts` - Prefixes and counts for claims this domain makes about DOIs in its meta tags.
        - `«prefix»` : «count» - The prefix and count. e.g. "this domain makes 5 claims to have DOIs with this prefix".


#### http-domains
Observations made about domains of **destination URLs** arrived at from **HTTP**.

- `«domain»` - The domain in question
    - `total` : «count» - Total number of http-domain samples for this domain.
    - `source-counts` - Sources and counts from which samples were taken to arrive at this domain.
        - `«source»` : «count» - The source (one of "crossref-api" or "datacite-api") and the number of samples. e.g. "this Destination URL Domain was arrived at from the Crossref API from 5 samples".
    - `prefix-counts` - Prefixes and counts for claims this domain makes about DOIs in its meta tags.
        - `«prefix»` : «count» - The prefix and count. e.g. "this domain makes 5 claims to have DOIs with this prefix".

#### browser-domains
Observations made about domains of **destination URLs** arrived at from the **Browser**.

- `«domain»` - The domain in question
    - `total` : «count» - Total number of browser-domain samples for this domain.
    - `source-counts` - Sources and counts from which samples were taken to arrive at this domain.
        - `«source»` : «count» - The source (one of "crossref-api" or "datacite-api") and the number of samples. e.g. "this Destination URL Domain was arrived at from the Crossref API from 5 samples".
    - `prefix-counts` - Prefixes and counts for claims this domain makes about DOIs in its meta tags.
        - `«prefix»` : «count» - The prefix and count. e.g. "this domain makes 5 claims to have DOIs with this prefix".


#### union-domains
Observations made about domains found in the `union` section, i.e. the distinct union of the Resource URL Domain, HTTP domains (including redirects) and Browser domains (including redirects)

- `«domain»` - The domain in question
    - `total` : «count» - Total number of samples for this domain.
    - `source-counts` - Sources and counts from which samples were taken to arrive at this domain.
        - `«source»` : «count» - The source (one of "crossref-api" or "datacite-api") and the number of samples. e.g. "this Destination URL Domain was arrived at from the Crossref API from 5 samples".
    - `prefix-counts` - Prefixes and counts for claims this domain makes about DOIs in its meta tags.
        - `«prefix»` : «count» - The prefix and count. e.g. "this domain makes 5 claims to have DOIs with this prefix".


#### doi-roundtrip

Roundtrips for DOIs.

- `«domain»` - The domain in question
    - `best-roundtrip-counts` - Count of the the best methods for roundtripping the DOI for this domain.
        - `http` : «count» - Number of samples for which HTTP was the best way of roundtripping.
        - `browser` : «count» - Number of samples for which Browser was the best way of roundtripping.
        - `NULL` : «count» - Number of samples for which there was no way of roundtripping.
    - `prefixes` - Per prefix samples were taken from.
        - `«prefix»` - The prefix in question
            - `http` : «count» - Number of samples for which HTTP was the best way of roundtripping this prefix to this domain.
            - `browser` : «count» - Number of samples for which Browser was the best way of roundtripping this prefix to this domain.
            - `NULL` : «count» - Number of samples for which there was no way of roundtripping this prefix to this domain.

#### prefix-roundtrip

Roundtrips for DOI prefixes.

- `«domain»` - The domain in question
    - `best-roundtrip-counts` - Count of the the best methods for roundtripping the DOI for this domain.
        - `http` : «count» - Number of samples for which HTTP was the best way of roundtripping.
        - `browser` : «count» - Number of samples for which Browser was the best way of roundtripping.
        - `NULL` : «count» - Number of samples for which there was no way of roundtripping.
    - `prefixes` - Per prefix samples were taken from.
        - `«prefix»` - The prefix in question
            - `http` : «count» - Number of samples for which HTTP was the best way of roundtripping this prefix to this domain.
            - `browser` : «count» - Number of samples for which Browser was the best way of roundtripping this prefix to this domain.
            - `NULL` : «count» - Number of samples for which there was no way of roundtripping this prefix to this domain.





## Artifact

The `content-heuristics` Artifact is generated from an Aggregation. It produces a heuristic from which decisions can be made.

Roundtrips are classified into four categories:

- `zero` - None made.
- `low`
- `medium`
- `high`
- `full` - Every attempt succeeded.

The thresholds for these are arrived at experimentally and hard-coded, but are included in the Artifact. 

- `thresholds`
    - `low` - threshold for 'low', for example 0.25
    - `medium` - threshold for 'low', for example 0.5
    - `high` - threshold for 'low', for example 0.75
- `prefixes` - List of all prefixes.
- `all-domains` - List of all domains found anywhere.
- `roundtrip-domains` - Lists of domains classified by their roundrip success.

## License

Copyright © Crossref

Distributed under the The MIT License (MIT).
