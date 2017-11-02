docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_sample --partitions 10 --replication-factor 1 --if-not-exists'

docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_observation --partitions 10 --replication-factor 1 --if-not-exists'

docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_extraction --partitions 10 --replication-factor 1 --if-not-exists'

docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_analysis --partitions 10 --replication-factor 1 --if-not-exists'
