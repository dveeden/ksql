package io.confluent.ksql.metastore;


import io.confluent.ksql.serde.KQLTopicSerDe;
import io.confluent.ksql.util.KSQLException;

public class KafkaTopic implements DataSource {

  final String topicName;
  final String kafkaTopicName;
  final KQLTopicSerDe kqlTopicSerDe;
  final DataSourceType dataSourceType;

  public KafkaTopic(String topicName, String kafkaTopicName, KQLTopicSerDe kqlTopicSerDe) {
    this.topicName = topicName;
    this.kafkaTopicName = kafkaTopicName;
    this.kqlTopicSerDe = kqlTopicSerDe;
    this.dataSourceType = DataSourceType.KTOPIC;
  }

  public KQLTopicSerDe getKqlTopicSerDe() {
    return kqlTopicSerDe;
  }

  public String getKafkaTopicName() {
    return kafkaTopicName;
  }

  public String getTopicName() {
    return topicName;
  }

  public static DataSourceSerDe getDataSpDataSourceSerDe(String dataSourceSerdeName) {
    if (dataSourceSerdeName.equalsIgnoreCase("json")) {
      return DataSourceSerDe.JSON;
    } else if (dataSourceSerdeName.equalsIgnoreCase("avro")) {
      return DataSourceSerDe.AVRO;
    }
    throw new KSQLException("DataSource Type is not supported: " + dataSourceSerdeName);
  }

  @Override
  public String getName() {
    return topicName;
  }

  @Override
  public DataSourceType getDataSourceType() {
    return dataSourceType;
  }
}
