
# Example Project

An example project or sample code will be provided in the future to demonstrate how to use this module in a `zio-flow` app. In the meantime, our integration test suite (under `it` subdirectory) provides some good examples. Mainly, a `CqlSession` needs to be provided. See `CassandraTestContainerSupport.scala` in the test suite on how to construct a `CqlSession`. 

# Supported Versions

The Cassandra module supports Cassandra 3.x, Cassandra 4.x and ScyllaDB 4.x. Specifically, we test against Cassandra 3.11, Cassandra 4.1, and ScyllaDB 4.5. See `CassandraKeyValueStoreSpec.scala` in the test suite for more details. 

# Database Setup

The Cassandra module requires a table (column family) for persistence. To create this table, run the following CQL statement:

```cql
CREATE TABLE _zflow_key_value_store (
  zflow_kv_namespace  VARCHAR,
  zflow_kv_key        BLOB,
  zflow_kv_timestamp  BIGINT,
  zflow_kv_value      BLOB,
  PRIMARY KEY (zflow_kv_namespace, zflow_kv_key, zflow_kv_timestamp)
);
```
You should add table options to this statement in a production environment for tuning. Please consult the official documentations of the database product of your choosing. 

# Performance/Scaling Considerations:

As you can see from the CQL above, the primary key is composed of the three columns, `zflow_kv_namespace`, `zflow_kv_key` and `zflow_kv_timestamp`. In particular, `zflow_kv_namespace` is the partition key and `zflow_kv_key` and `zflow_kv_timestamp` are the clustering keys. Assuming the default partitioner (Murmur3Partitioner) is used, data will be partitioned by the hash values of the `zflow_kv_namespace` column. If one small set of namespace values are the majority for all possible values, that will create data skew and can have a big impact to your cluster down the road. Some consideration is needed when deciding the type of values `zflow_kv_namespace` should store.
