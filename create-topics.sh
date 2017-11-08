docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_sample --partitions 100 --replication-factor 1 --if-not-exists'

docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_observation --partitions 100 --replication-factor 1 --if-not-exists'

docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_extraction --partitions 100 --replication-factor 1 --if-not-exists'

docker-compose exec  kafka bash -c '$KAFKA_HOME/bin/kafka-topics.sh --zookeeper localhost --create --topic visitor_analysis --partitions 100 --replication-factor 1 --if-not-exists'
