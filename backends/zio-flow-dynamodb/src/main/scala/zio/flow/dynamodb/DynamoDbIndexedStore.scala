/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import zio.aws.core.{AwsError, GenericAwsError}
import zio.aws.dynamodb.DynamoDb
import zio.aws.dynamodb.model.primitives._
import zio.aws.dynamodb.model._
import zio.flow.runtime.IndexedStore
import zio.flow.runtime.IndexedStore.Index
import zio.prelude.data.Optional
import zio.stream.ZStream
import zio.{Chunk, IO, Schedule, URLayer, ZIO, ZLayer}

import java.io.IOException

final class DynamoDbIndexedStore(dynamoDb: DynamoDb) extends IndexedStore {
  import DynamoDbIndexedStore._

  override def position(topic: String): IO[IOException, Index] =
    getLatestPosition(topic).mapError(ioExceptionOf(s"Indexed store failed to get latest position of $topic topic", _))

  override def put(topic: String, value: Chunk[Byte]): IO[IOException, Index] =
    (for {
      currentIndex <- getLatestPosition(topic)
      nextIndex     = currentIndex.next
      // Updating position first
      _ <- dynamoDb.putItem(
             PutItemRequest(
               tableName,
               item = Map(
                 AttributeName(topicColumnName) -> stringValue(topic),
                 AttributeName(indexColumnName) -> longValue(positionIndex),
                 AttributeName(valueColumnName) -> longValue(nextIndex.toLong)
               ),
               conditionExpression = ConditionExpression(
                 s"$valueColumnName = ${RequestExpressionPlaceholder.indexColumn} OR attribute_not_exists($valueColumnName)"
               ),
               expressionAttributeValues = Map(
                 ExpressionAttributeValueVariable(RequestExpressionPlaceholder.indexColumn) ->
                   longValue(currentIndex)
               )
             )
           )
      // If succeeded then nextIndex is ours to set
      _ <- dynamoDb.putItem(
             PutItemRequest(
               tableName,
               item = Map(
                 AttributeName(topicColumnName) -> stringValue(topic),
                 AttributeName(indexColumnName) -> longValue(nextIndex),
                 AttributeName(valueColumnName) -> binaryValue(value)
               )
             )
           )
    } yield nextIndex)
      .retry(Schedule.recurWhile {
        case GenericAwsError(_: ConditionalCheckFailedException) =>
          true
        case _ =>
          false
      })
      .mapError(ioExceptionOf(s"Indexed store failed to put new value in topic $topic", _))

  override def scan(topic: String, position: Index, until: Index): ZStream[Any, IOException, Chunk[Byte]] =
    dynamoDb
      .query(
        QueryRequest(
          tableName,
          keyConditionExpression = KeyExpression(
            s"$topicColumnName=${RequestExpressionPlaceholder.topicColumn} AND (${indexColumnName} BETWEEN ${RequestExpressionPlaceholder.fromIndex} AND ${RequestExpressionPlaceholder.toIndex})"
          ),
          expressionAttributeValues = Map(
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.topicColumn) -> stringValue(topic),
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.fromIndex)   -> longValue(position.toLong),
            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.toIndex)     -> longValue(until.toLong)
          )
        )
      )
      .mapZIO { values =>
        for {
          value <-
            ZIO
              .fromOption(values.get(AttributeName(valueColumnName)))
              .orElseFail(
                GenericAwsError(new IllegalStateException(s"DynamoDb response does not have $valueColumnName column"))
              )
          b <- value.getB
        } yield b
      }
      .mapError(ioExceptionOf(s"Indexed store failed to query topic $topic", _))

  override def delete(topic: String): IO[Throwable, Unit] =
    getLatestPosition(topic).flatMap { latestIndex =>
      val indices = -1L to latestIndex
      val deletes: Iterable[WriteRequest] = indices.map(idx =>
        WriteRequest(deleteRequest =
          DeleteRequest(
            Map(
              AttributeName(topicColumnName) -> stringValue(topic),
              AttributeName(indexColumnName) -> longValue(idx)
            )
          )
        )
      )

      dynamoDb
        .batchWriteItem(
          BatchWriteItemRequest(requestItems =
            Map(
              tableName -> deletes
            )
          )
        )
    }.mapError(ioExceptionOf(s"Indexed store failed to delete topic $topic", _)).unit

  private def getLatestPosition(topic: String): IO[AwsError, Index] =
    for {
      response <- dynamoDb
                    .getItem(
                      GetItemRequest(
                        tableName,
                        key = Map(
                          AttributeName(topicColumnName) -> stringValue(topic),
                          AttributeName(indexColumnName) -> longValue(positionIndex)
                        )
                      )
                    )
      index <- response.item match {
                 case Optional.Present(item) =>
                   item.get(AttributeName(valueColumnName)) match {
                     case Some(value) =>
                       for {
                         n     <- value.getN
                         index <- ZIO.attempt(n.toLong).mapError(GenericAwsError)
                       } yield Index(index)
                     case None => ZIO.succeed(Index(0L))
                   }
                 case Optional.Absent => ZIO.succeed(Index(0L))
               }
    } yield index
}

object DynamoDbIndexedStore {
  val layer: URLayer[DynamoDb, IndexedStore] =
    ZLayer {
      ZIO
        .service[DynamoDb]
        .map(new DynamoDbIndexedStore(_))
    }

  private[dynamodb] val tableName: TableName = TableName("_zflow_indexed_store")

  private[dynamodb] val topicColumnName: String =
    withColumnPrefix("topic")

  private[dynamodb] val indexColumnName: String =
    withColumnPrefix("index")

  private[dynamodb] val valueColumnName: String =
    withColumnPrefix("value")

  private[dynamodb] val positionIndex: Long = -1L

  private object RequestExpressionPlaceholder {
    val indexColumn: String = ":idx"
    val topicColumn: String = ":topic"
    val fromIndex: String   = ":fromIdx"
    val toIndex: String     = ":toIdx"
  }

  // TODO: unify helpers with kvstore

  private def binaryValue(b: Chunk[Byte]): AttributeValue =
    AttributeValue(
      b = BinaryAttributeValue(b)
    )

  private def stringValue(s: String): AttributeValue =
    AttributeValue(
      s = StringAttributeValue(s)
    )

  private def longValue(n: Long): AttributeValue =
    AttributeValue(
      n = NumberAttributeValue(n.toString())
    )

  private def ioExceptionOf(errorContext: String, awsError: AwsError): IOException =
    wrapThrowable(errorContext, awsError.toThrowable)

  private def wrapThrowable(errorContext: String, error: Throwable): IOException =
    new IOException(s"$errorContext: <${error.getMessage}>.", error)

  private def withColumnPrefix(s: String) =
    "zflow_idx_" + s
}
