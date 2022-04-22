package zio.flow.dynamodb

import zio.aws.core.AwsError
import zio.aws.dynamodb.DynamoDb
import zio.aws.dynamodb.model.primitives._
import zio.aws.dynamodb.model._
import zio.flow.RemoteVariableVersion
import zio.flow.dynamodb.DynamoDbKeyValueStore._
import zio.flow.internal.KeyValueStore
import zio.stream.ZStream
import zio.{Chunk, IO, URLayer, ZIO}

import java.io.IOException
import scala.util.Try

/*
Idea for a safer encoding:

- Store each version of values so always put with retrying on version collision
- Return the version in put
- The recording context just records the versions, but immediately stores in the store
- Store each used variable's latest version in the state and use it in the context for getting them
- When getting, use the latest version if it's not in the state
- Ability to delete all versions
=> This way there is no need for putAll at all
 */

final class DynamoDbKeyValueStore(dynamoDB: DynamoDb) extends KeyValueStore {

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte]
  ): IO[IOException, Boolean] =
    putAll(Chunk(KeyValueStore.Item(namespace, key, value))).as(true)

  override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] = {

    val request =
      GetItemRequest(
        tableName,
        dynamoDbKey(namespace, key),
        projectionExpression = Option(
          ProjectionExpression(valueColumnName)
        )
      )

    dynamoDB
      .getItem(request)
      .mapBoth(
        ioExceptionOf(s"Error retrieving or reading value for <$namespace> namespace", _),
        result =>
          for {
            item      <- result.item.toOption
            value     <- item.get(AttributeName(valueColumnName))
            byteChunk <- value.b.toOption
          } yield byteChunk
      )
  }

  override def getVersion(namespace: String, key: Chunk[Byte]): IO[IOException, Option[RemoteVariableVersion]] = {

    val request =
      GetItemRequest(
        tableName,
        dynamoDbKey(namespace, key),
        projectionExpression = Option(
          ProjectionExpression(versionColumnName)
        )
      )

    dynamoDB
      .getItem(request)
      .mapBoth(
        ioExceptionOf(s"Error retrieving or reading version for <$namespace> namespace", _),
        result =>
          for {
            item         <- result.item.toOption
            value        <- item.get(AttributeName(versionColumnName))
            numberString <- value.n.toOption
            number       <- Try(numberString.toLong).toOption
          } yield RemoteVariableVersion(number)
      )
  }

  override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] = {

    val request = scanRequest.copy(
      expressionAttributeValues = Option(
        Map(
          ExpressionAttributeValueVariable(RequestExpressionPlaceholder.namespaceColumn) -> stringValue(namespace)
        )
      )
    )

    dynamoDB
      .scan(request)
      .mapBoth(
        ioExceptionOf(s"Error scanning all key-value pairs for <$namespace> namespace", _),
        item =>
          for {
            key            <- item.get(AttributeName(keyColumnName))
            keyByteChunk   <- key.b.toOption
            value          <- item.get(AttributeName(valueColumnName))
            valueByteChunk <- value.b.toOption
          } yield {
            keyByteChunk -> valueByteChunk
          }
      )
      .collect { case Some(byteChunkPair) =>
        byteChunkPair
      }
  }

  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
    dynamoDB
      .deleteItem(
        DeleteItemRequest(
          tableName = tableName,
          key = dynamoDbKey(namespace, key)
        )
      )
      .mapBoth(
        ioExceptionOf(s"Error deleting item from <$namespace> namespace", _),
        _ => ()
      )

  override def putAll(items: Chunk[KeyValueStore.Item]): IO[IOException, Unit] =
    dynamoDB
      .transactWriteItems(
        TransactWriteItemsRequest(
          transactItems = items.flatMap { item =>
            Chunk(
              TransactWriteItem(
                put = Put(
                  tableName = tableName,
                  item = dynamoDbKey(item.namespace, item.key) +
                    (AttributeName(valueColumnName) -> binaryValue(item.value))
                )
              ),
              TransactWriteItem(
                put = Put(
                  tableName = tableName,
                  conditionExpression = ConditionExpression(s"attribute_not_exists($versionColumnName)"),
                  item = dynamoDbKey(item.namespace, item.key) +
                    (AttributeName(versionColumnName) -> AttributeValue(n = Some(NumberAttributeValue("0"))))
                )
              ),
              TransactWriteItem(
                update = Update(
                  tableName = tableName,
                  conditionExpression = ConditionExpression(s"attribute_exists($versionColumnName)"),
                  updateExpression = UpdateExpression(
                    s"SET $versionColumnName = $versionColumnName + ${RequestExpressionPlaceholder.increment}"
                  ),
                  key = dynamoDbKey(item.namespace, item.key),
                  expressionAttributeValues = Option(
                    Map(
                      ExpressionAttributeValueVariable(RequestExpressionPlaceholder.increment) -> AttributeValue(n =
                        Some(NumberAttributeValue("1"))
                      )
                    )
                  )
                )
              )
            )
          }
        )
      )
      .mapBoth(
        ioExceptionOf(s"Error putting multiple items transactionally", _),
        _ => ()
      )
}

object DynamoDbKeyValueStore {
  val layer: URLayer[DynamoDb, KeyValueStore] =
    ZIO
      .service[DynamoDb]
      .map(new DynamoDbKeyValueStore(_))
      .toLayer

  private[dynamodb] val tableName: TableName = TableName("_zflow_key_value_store")

  private[dynamodb] val namespaceColumnName: String =
    withColumnPrefix("namespace")

  private[dynamodb] val keyColumnName: String =
    withColumnPrefix("key")

  private[dynamodb] val valueColumnName: String =
    withColumnPrefix("value")

  private[dynamodb] val versionColumnName: String =
    withColumnPrefix("version")

  private object RequestExpressionPlaceholder {
    val namespaceColumn: String = ":namespaceValue"
    val increment: String       = ":increment"
  }

  private val scanRequest: ScanRequest =
    ScanRequest(
      tableName,
      projectionExpression = Option(
        ProjectionExpression(
          Seq(keyColumnName, valueColumnName).mkString(", ")
        )
      ),
      filterExpression = Option(
        ConditionExpression(
          s"$namespaceColumnName = ${RequestExpressionPlaceholder.namespaceColumn}"
        )
      )
    )

  private def binaryValue(b: Chunk[Byte]): AttributeValue =
    AttributeValue(
      b = Option(BinaryAttributeValue(b))
    )

  private def stringValue(s: String): AttributeValue =
    AttributeValue(
      s = Option(StringAttributeValue(s))
    )

  private def dynamoDbKey(namespace: String, key: Chunk[Byte]): Map[AttributeName, AttributeValue] =
    Map(
      AttributeName(namespaceColumnName) -> stringValue(namespace),
      AttributeName(keyColumnName)       -> binaryValue(key)
    )

  private def ioExceptionOf(errorContext: String, awsError: AwsError): IOException = {
    val error = awsError.toThrowable

    new IOException(s"$errorContext: <${error.getMessage}>.", error)
  }

  private def withColumnPrefix(s: String) =
    "zflow_kv_" + s
}
