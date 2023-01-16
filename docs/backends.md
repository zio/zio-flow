---
id: backends
title: "Backends"
---

# Backends

This page lists all the provided _backend implementations_ for storing persistent data in the ZIO Flow executors.
Backend implementations need to implement two interfaces:

- `KeyValueStore`
- `IndexedStore`

Custom backend implementations are possible by implementing these traits, and a shared _test suite_ is published to
validate the custom implementations (more information about this can be found in
the [testing section](testing#testing-backends).

## RocksDB

The RocksDb backend is implemented in the following module:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-rocksdb" % "@VERSION@"
```

RocksDb databases are stored in local files. You can use the same file for the key-value store and the indexed store,
but you don't have to.

## Cassandra

To use Cassandra as a ZIO Flow backend, add the following dependency:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-cassandra" % "@VERSION@"
```

### Supported Versions

The Cassandra module supports Cassandra 3.x, Cassandra 4.x and ScyllaDB 4.x. Specifically, we test against Cassandra
3.11, Cassandra 4.1, and ScyllaDB 4.5. See `CassandraKeyValueStoreSpec.scala` in the test suite for more details.

### Database Setup

The Cassandra module requires two tables (column family) for persistence.

To create these tables, run the following CQL statements:

for key-value store:

```cql
CREATE TABLE _zflow_key_value_store (
  zflow_kv_namespace  VARCHAR,
  zflow_kv_key        BLOB,
  zflow_kv_timestamp  BIGINT,
  zflow_kv_value      BLOB,
  PRIMARY KEY (zflow_kv_namespace, zflow_kv_key, zflow_kv_timestamp)
);
```

for indexed store:

```cql
CREATE TABLE _zflow_idx_store (
  zflow_idx_topic  VARCHAR,
  zflow_idx_index  BIGINT,
  zflow_idx_value  BLOB,
  PRIMARY KEY (zflow_idx_topic, zflow_idx_index)
);
```

You should add table options to this statement in a production environment for tuning. Please consult the official
documentations of the database product of your choosing.

### Performance/Scaling Considerations:

As you can see from the CQL above, the primary key is composed of the three columns, `zflow_kv_namespace`
, `zflow_kv_key` and `zflow_kv_timestamp`. In particular, `zflow_kv_namespace` is the partition key and `zflow_kv_key`
and `zflow_kv_timestamp` are the clustering keys. Assuming the default partitioner (Murmur3Partitioner) is used, data
will be partitioned by the hash values of the `zflow_kv_namespace` column. If one small set of namespace values are the
majority for all possible values, that will create data skew and can have a big impact to your cluster down the road.
Some consideration is needed when deciding the type of values `zflow_kv_namespace` should store.

## DynamoDB

To use AWS DynamoDb as a key-value store or indexed store implementation add the following dependency:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-dynamodb" % "@VERSION@"
```

### Metrics
The DynamoDb backend does not publish any metrics by default, but you can use `zio-aws`'s built-in metrics aspect to 
enable AWS operation level metrics:

```scala
DynamoDb.live @@ zio.aws.core.aspects.callDuration(
  prefix = "zioflow",
  boundaries = Histogram.Boundaries.exponential(0.01, 2, 14)
)
```

### Database Setup

The DynamoDB module requires two tables for persistence (one for key-value store, one for indexed store).
Here's a Python script to create the table:

```python
import boto3

dynamodb = boto3.resource('dynamodb')

dynamodb.create_table (
    TableName = '_zflow_key_value_store',
    AttributeDefinitions = [
         {
             'AttributeName': 'zflow_kv_key',
             'AttributeType': 'B'
         },
         {
             'AttributeName': 'zflow_kv_timestamp',
             'AttributeType': 'N'
         }
    ],    
    KeySchema = [
        {
            'AttributeName': 'zflow_kv_key',
            'KeyType': 'HASH'
        },
        {
            'AttributeName': 'zflow_kv_timestamp',
            'KeyType': 'RANGE'
        }
    ],
    GlobalSecondaryIndexes = [
        {
            'IndexName': 'namespace_index',
            'KeySchema': [
                {
                    'AttributeName': 'zflow_kv_namespace',
                    'KeyType': 'HASH'
                }
            ],
            'Projection': {
                'NonKeyAttributes': ['zflow_kv_value'],
                'ProjectionType': 'INCLUDE'
            },
            'ProvisionedThroughput' = {
                'ReadCapacityUnits': 1,
                'WriteCapacityUnits': 1
            }
        }
    ],
    ProvisionedThroughput = {
        'ReadCapacityUnits': 1,
        'WriteCapacityUnits': 1
    }
)

dynamodb.create_table (
    TableName = '_zflow_indexed_store',
    AttributeDefinitions = [
         {
             'AttributeName': 'zflow_idx_topic',
             'AttributeType': 'S'
         },
         {
             'AttributeName': 'zflow_idx_index',
             'AttributeType': 'N'
         }
    ],    
    KeySchema = [
        {
            'AttributeName': 'zflow_idx_topic',
            'KeyType': 'HASH'
        },
        {
            'AttributeName': 'zflow_idx_index',
            'KeyType': 'RANGE'
        }
    ],    
    ProvisionedThroughput = {
        'ReadCapacityUnits': 1,
        'WriteCapacityUnits': 1
    }
)
```

Of course, you can use your favourite AWS tool to create the table (e.g. DynamoDB Console) or to automate the creation (
e.g. via CloudFormation). You should customize the `ProvisionedThroughput` settings when the table is provisioned.

### Performance/Scaling Considerations:

As you can see from the script above, the primary key is composed of the two columns, `zflow_kv_key`
and `zflow_kv_timestamp`. In particular, `zflow_kv_key` is the partition key and `zflow_kv_timestamp` is the sort key.
Internally, zio-flow will store the namespace as part of the `zflow_kv_key` value as well, but it will also
store it in the `zflow_kv_namespace` attribute for easy access to all values within a namespace. This requires
a secondary index to be set up on the `zflow_kv_namespace` attribute.

## In-memory

ZIO Flow also provides a default in-memory implementation for both the key-value store and the indexed store. These are
useful for running ZIO Flow programs in tests, but they are not safe to use in production.

The following layers create the in-memory implementations of the stores:

```scala mdoc
import zio.flow.runtime._

KeyValueStore.inMemory
IndexedStore.inMemory
```