/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.dynamodb

import zio.aws.core.AwsError
import zio.aws.dynamodb.DynamoDb
import zio.aws.dynamodb.model.primitives._
import zio.aws.dynamodb.model.{AttributeValue, ScanRequest, UpdateItemRequest}
import DynamoDbKeyValueStore._
import zio.flow.internal.KeyValueStore
import zio.stream.ZStream
import zio.{Chunk, IO, URLayer, ZIO, ZLayer}

import java.io.IOException
import zio.flow.internal.Timestamp
import java.nio.charset.StandardCharsets
import zio.aws.dynamodb.model.QueryRequest
import zio.aws.dynamodb.model.BatchWriteItemRequest
import zio.aws.dynamodb.model.WriteRequest
import zio.prelude.data.Optional
import zio.aws.dynamodb.model.DeleteRequest
import scala.util.Try

final class DynamoDbKeyValueStore(dynamoDB: DynamoDb) extends KeyValueStore {

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte],
    timestamp: Timestamp
  ): IO[IOException, Boolean] = {

    val request =
      UpdateItemRequest(
        tableName,
        dynamoDbKey(namespace, key, timestamp),
        updateExpression = Option(
          UpdateExpression(
            s"SET $valueColumnName = ${RequestExpressionPlaceholder.valueColumn}, $namespaceColumnName = ${RequestExpressionPlaceholder.namespaceColumn}"
          )
        ),
        expressionAttributeValues = Option(
          Map(
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.valueColumn)     -> binaryValue(value),
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.namespaceColumn) -> stringValue(namespace)
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

  def getLatestImpl(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): IO[IOException, Option[(Chunk[Byte], Timestamp)]] = {
    val timestampCondition =
      before.map(_ => s" AND $timestampName <= ${RequestExpressionPlaceholder.timestampColumn}").getOrElse("")

    val request =
      QueryRequest(
        tableName,
        limit = Option(PositiveIntegerObject(1)),
        scanIndexForward = Option(false),
        keyConditionExpression = Option(
          KeyExpression(s"$keyColumnName = ${RequestExpressionPlaceholder.keyColumn}$timestampCondition")
        ),
        expressionAttributeValues = Option(
          Map(
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.keyColumn) -> dynamoDbKeyValue(namespace, key)
          ) ++ before.map(timestamp =>
            (ExpressionAttributeValueVariable(RequestExpressionPlaceholder.timestampColumn)) -> longValue(
              timestamp.value
            )
          )
        )
      )

    dynamoDB
      .query(request)
      .mapBoth(
        ioExceptionOf(s"Error retrieving or reading value for <$namespace> namespace", _),
        result =>
          for {
            rawBytes     <- result.get(AttributeName(valueColumnName))
            rawTimestamp <- result.get(AttributeName(timestampName))
            byteChunk    <- rawBytes.b.toOption
            timestampStr <- rawTimestamp.n.toOption
            timestamp    <- Try(timestampStr.toLong).toOption
          } yield (byteChunk, Timestamp(timestamp))
      )
      .flatMap(bytesOption => ZStream.fromIterable(bytesOption))
      .runHead
  }

  override def getLatest(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): IO[IOException, Option[Chunk[Byte]]] =
    getLatestImpl(namespace, key, before).map(_.map(_._1))

  def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Timestamp]] =
    getLatestImpl(namespace, key, before = None).map(_.map(_._2))

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
            namespaceBytes  = namespace.getBytes(StandardCharsets.UTF_8)
          } yield {
            keyByteChunk.drop(namespaceBytes.size) -> valueByteChunk
          }
      )
      .collect { case Some(byteChunkPair) =>
        byteChunkPair
      }
  }

  private def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[Any, IOException, Timestamp] = {
    val request =
      QueryRequest(
        tableName,
        limit = Option(PositiveIntegerObject(1)),
        scanIndexForward = Option(false),
        keyConditionExpression = Option(
          KeyExpression(s"$keyColumnName = ${RequestExpressionPlaceholder.keyColumn}")
        ),
        expressionAttributeValues = Option(
          Map(
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.keyColumn) -> dynamoDbKeyValue(namespace, key)
          )
        )
      )

    dynamoDB
      .query(request)
      .mapBoth(
        ioExceptionOf(s"Error retrieving or reading value for <$namespace> namespace", _),
        result =>
          for {
            rawTimestamp <- result.get(AttributeName(timestampName))
            timestampStr <- rawTimestamp.n.toOption
            timestamp    <- Try(timestampStr.toLong).toOption
          } yield Timestamp(timestamp)
      )
      .flatMap(bytesOption => ZStream.fromIterable(bytesOption))
  }

  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
    getAllTimestamps(namespace, key)
      .map(timestamp => DeleteRequest(dynamoDbKey(namespace, key, timestamp)))
      .grouped(25)
      .mapZIO(items =>
        dynamoDB
          .batchWriteItem(
            BatchWriteItemRequest(
              Map(
                tableName -> items.map(i => WriteRequest(deleteRequest = Optional.Present(i)))
              )
            )
          )
          .mapError(ioExceptionOf(s"Error retrieving or reading value for <$namespace> namespace", _))
      )
      .runDrain

}

object DynamoDbKeyValueStore {
  val layer: URLayer[DynamoDb, KeyValueStore] =
    ZLayer {
      ZIO
        .service[DynamoDb]
        .map(new DynamoDbKeyValueStore(_))
    }

  private[dynamodb] val tableName: TableName = TableName("_zflow_key_value_store")

  private[dynamodb] val namespaceColumnName: String =
    withColumnPrefix("namespace")

  private[dynamodb] val keyColumnName: String =
    withColumnPrefix("key")

  private[dynamodb] val valueColumnName: String =
    withColumnPrefix("value")

  private[dynamodb] val timestampName: String =
    withColumnPrefix("timestamp")

  private object RequestExpressionPlaceholder {
    val keyColumn: String       = ":keyValue"
    val namespaceColumn: String = ":namespaceValue"
    val valueColumn: String     = ":valueColumnValue"
    val timestampColumn: String = ":timestampValue"
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

  private def longValue(n: Long): AttributeValue =
    AttributeValue(
      n = Option(NumberAttributeValue(n.toString()))
    )

  private def dynamoDbKeyValue(namespace: String, key: Chunk[Byte]): AttributeValue =
    binaryValue(Chunk.fromArray(namespace.getBytes(StandardCharsets.UTF_8)) ++ key)

  private def dynamoDbKey(
    namespace: String,
    key: Chunk[Byte],
    timestamp: Timestamp
  ): Map[AttributeName, AttributeValue] =
    Map(
      AttributeName(keyColumnName) -> dynamoDbKeyValue(namespace, key),
      AttributeName(timestampName) -> longValue(timestamp.value)
    )

  private def ioExceptionOf(errorContext: String, awsError: AwsError): IOException = {
    val error = awsError.toThrowable

    new IOException(s"$errorContext: <${error.getMessage}>.", error)
  }

  private def withColumnPrefix(s: String) =
    "zflow_kv_" + s
}
