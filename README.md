# Web Visitor

Experimental labs project. 

Visit a selection of DOIs' landing pages.
    
## Config

  - VISITOR_DOI_SAMPLE_TOPIC - Kafka topic for DOI samples
  - GLOBAL_KAFKA_BOOTSTRAP_SERVERS - Kafka bootstrap servers

A Kafka topic should be created and the name should be supplied as VISITOR_DOI_SAMPLE_TOPIC. It should have a largeish number of partitions, e.g. at least 100, to allow for parallel processing.

## To run

### Sample

    lein run sample

Run the sampler as a continuous process. It takes a random sample of DOIs from the REST API, shuffles them, and sends them to a Kafka queue. This happens continuously.

### Sample one off

    lein run one-sample

To ingest one complete sample for all members and exit.

## License

Copyright © Crossref

Distributed under the The MIT License (MIT).
