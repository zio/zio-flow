---
id: dynamodb-key-value-store
title: "DynamoDB Key-value Store"
---

# Example Project

An example project or sample code will be provided in the future to demonstrate how to use this module in a `zio-flow` app. In the meantime, our integration test suite (`DynamoDbKeyValueStoreSpec.scala` under `it` subdirectory) provides some good examples.

# Database Setup

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

# Performance/Scaling Considerations:

As you can see from the script above, the primary key is composed of the two columns, `zflow_kv_key` and `zflow_kv_timestamp`. In particular, `zflow_kv_key` is the partition key and `zflow_kv_timestamp` is the sort key. Internally, zio-flow will store the namespace as part of the `zflow_kv_key` value as well, but it will also
store it in the `zflow_kv_namespace` attribute for easy access to all values within a namespace. This requires
a secondary index to be set up on the `zflow_kv_namespace` attribute.
