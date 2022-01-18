
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
             'AttributeName': 'zflow_kv_namespace',
             'AttributeType': 'S'
         },
         {
             'AttributeName': 'zflow_kv_key',
             'AttributeType': 'B'
         }
    ],    
    KeySchema = [
        {
            'AttributeName': 'zflow_kv_namespace',
            'KeyType': 'HASH'
        },
        {
            'AttributeName': 'zflow_kv_key',
            'KeyType': 'RANGE'
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

As you can see from the script above, the primary key is composed of the two columns, `zflow_kv_namespace` and `zflow_kv_key`. In particular, `zflow_kv_namespace` is the partition key and `zflow_kv_key` is the sort key. Data will be partitioned by the hash values of the `zflow_kv_namespace` column. If one small set of namespace values are the majority for all possible values, that will create data skew and can have a big impact to your cluster down the road. Some consideration is needed when deciding the type of values `zflow_kv_namespace` should store.
