package zio.flow.internal

import DynamoDbKeyValueStore._
import java.io.IOException
import zio.aws.core.AwsError
import zio.aws.dynamodb.DynamoDb
import zio.aws.dynamodb.model.primitives._
import zio.aws.dynamodb.model.{AttributeValue, GetItemRequest, ScanRequest, UpdateItemRequest}
import zio.stream.ZStream
import zio.{Chunk, IO, URLayer, ZIO}

final class DynamoDbKeyValueStore(dynamoDB: DynamoDb) extends KeyValueStore {

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte]
  ): IO[IOException, Boolean] = {

    val request =
      UpdateItemRequest(
        TableName(tableName),
        dynamoDbKey(namespace, key),
        updateExpression = Option(
          UpdateExpression(
            s"SET $valueColumnName = ${RequestExpressionPlaceholder.valueColumn}"
          )
        ),
        expressionAttributeValues = Option(
          Map(
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.valueColumn) -> binaryValue(value)
          )
        )
      )

    dynamoDB
      .updateItem(request)
      .mapBoth(
        ioExceptionOf(s"Error putting key-value pair for <$namespace> namespace", _),
        _ => true
      )
  }

  override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] = {

    val request =
      GetItemRequest(
        TableName(tableName),
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
            item      <- result.item
            value     <- item.get(AttributeName(valueColumnName))
            byteChunk <- value.b
          } yield byteChunk
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
            keyByteChunk   <- key.b
            value          <- item.get(AttributeName(valueColumnName))
            valueByteChunk <- value.b
          } yield {
            keyByteChunk -> valueByteChunk
          }
      )
      .collect { case Some(byteChunkPair) =>
        byteChunkPair
      }
  }
}

object DynamoDbKeyValueStore {

  val live: URLayer[DynamoDb, KeyValueStore] =
    ZIO
      .service[DynamoDb]
      .map(new DynamoDbKeyValueStore(_))
      .toLayer

  val tableName: String = "_zflow_key_value_store"

  val namespaceColumnName: String =
    withColumnPrefix("namespace")

  val keyColumnName: String =
    withColumnPrefix("key")

  val valueColumnName: String =
    withColumnPrefix("value")

  object RequestExpressionPlaceholder {

    val namespaceColumn: String = ":namespaceValue"

    val valueColumn: String = ":valueColumnValue"
  }

  val scanRequest: ScanRequest =
    ScanRequest(
      TableName(tableName),
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

  def binaryValue(b: Chunk[Byte]): AttributeValue =
    AttributeValue(
      b = Option(BinaryAttributeValue(b))
    )

  def stringValue(s: String): AttributeValue =
    AttributeValue(
      s = Option(StringAttributeValue(s))
    )

  def dynamoDbKey(namespace: String, key: Chunk[Byte]): Map[AttributeName, AttributeValue] =
    Map(
      AttributeName(namespaceColumnName) -> stringValue(namespace),
      AttributeName(keyColumnName)       -> binaryValue(key)
    )

  def ioExceptionOf(errorContext: String, awsError: AwsError): IOException = {
    val error = awsError.toThrowable

    new IOException(s"$errorContext: <${error.getMessage}>.", error)
  }

  private def withColumnPrefix(s: String) =
    "zflow_kv_" + s
}
