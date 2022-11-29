---
id: backends
title: "Backends"
---

# Backends

## RocksDB

## Cassandra

### Supported Versions

The Cassandra module supports Cassandra 3.x, Cassandra 4.x and ScyllaDB 4.x. Specifically, we test against Cassandra 3.11, Cassandra 4.1, and ScyllaDB 4.5. See `CassandraKeyValueStoreSpec.scala` in the test suite for more details.

### Database Setup

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

### Performance/Scaling Considerations:

As you can see from the CQL above, the primary key is composed of the three columns, `zflow_kv_namespace`, `zflow_kv_key` and `zflow_kv_timestamp`. In particular, `zflow_kv_namespace` is the partition key and `zflow_kv_key` and `zflow_kv_timestamp` are the clustering keys. Assuming the default partitioner (Murmur3Partitioner) is used, data will be partitioned by the hash values of the `zflow_kv_namespace` column. If one small set of namespace values are the majority for all possible values, that will create data skew and can have a big impact to your cluster down the road. Some consideration is needed when deciding the type of values `zflow_kv_namespace` should store.

## DynamoDB

### Database Setup

The DynamoDB module requires a table for persistence. Here's a Python script to create the table:

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
```

Of course, you can use your favourite AWS tool to create the table (e.g. DynamoDB Console) or to automate the creation (e.g. via CloudFormation). You should customize the `ProvisionedThroughput` settings when the table is provisioned.

### Performance/Scaling Considerations:

As you can see from the script above, the primary key is composed of the two columns, `zflow_kv_key` and `zflow_kv_timestamp`. In particular, `zflow_kv_key` is the partition key and `zflow_kv_timestamp` is the sort key. Internally, zio-flow will store the namespace as part of the `zflow_kv_key` value as well, but it will also
store it in the `zflow_kv_namespace` attribute for easy access to all values within a namespace. This requires
a secondary index to be set up on the `zflow_kv_namespace` attribute.

## In-memory