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

## License

Copyright © Crossref

Distributed under the The MIT License (MIT).
