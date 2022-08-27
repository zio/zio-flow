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

import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import zio.aws.core.{AwsError, GenericAwsError}
import zio.{Chunk, IO, Schedule}
import zio.aws.dynamodb.DynamoDb
import zio.aws.dynamodb.model.{AttributeValue, PutItemRequest}
import zio.aws.dynamodb.model.primitives._
import zio.flow.internal.IndexedStore
import zio.flow.internal.IndexedStore.Index
import zio.stream.ZStream

import java.io.IOException

final class DynamoDbIndexedStore(dynamoDb: DynamoDb) extends IndexedStore {
  import DynamoDbIndexedStore._

  override def position(topic: String): IO[IOException, Index] =
    getLatestPosition(topic).mapError(ioExceptionOf(s"Indexed store failed to get latest position of $topic topic", _))

  override def put(topic: String, value: Chunk[Byte]): IO[IOException, Index] =
    (for {
      currentIndex <- getLatestPosition(topic)
      nextIndex     = currentIndex.next
      _ <- dynamoDb.putItem(
             PutItemRequest(
               tableName,
               item = Map(
                 AttributeName(topicColumnName) -> stringValue(topic),
                 AttributeName(indexColumnName) -> longValue(nextIndex),
                 AttributeName(valueColumnName) -> binaryValue(value)
               ),
               conditionExpression = ConditionExpression(
                 s"$indexColumnName = ${RequestExpressionPlaceholder.indexColumn}"
               ),
               expressionAttributeValues = Map(
                 ExpressionAttributeValueVariable(RequestExpressionPlaceholder.indexColumn) ->
                   longValue(currentIndex)
               )
             )
           )
    } yield nextIndex)
      .retry(Schedule.recurWhile {
        case GenericAwsError(_: ConditionalCheckFailedException) => true
        case _                                                   => false
      })
      .mapError(ioExceptionOf(s"Indexed store failed to put new value in topic $topic", _))

  override def scan(topic: String, position: Index, until: Index): ZStream[Any, IOException, Chunk[Byte]] = ???

  private def getLatestPosition(topic: String): IO[AwsError, Index] = ???
}

object DynamoDbIndexedStore {
  private[dynamodb] val tableName: TableName = TableName("_zflow_indexed_store")

  private[dynamodb] val topicColumnName: String =
    withColumnPrefix("topic")

  private[dynamodb] val indexColumnName: String =
    withColumnPrefix("index")

  private[dynamodb] val valueColumnName: String =
    withColumnPrefix("value")

  private object RequestExpressionPlaceholder {
    val indexColumn: String = ":idx"
  }

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

  private def ioExceptionOf(errorContext: String, awsError: AwsError): IOException = {
    val error = awsError.toThrowable

    new IOException(s"$errorContext: <${error.getMessage}>.", error)
  }

  private def withColumnPrefix(s: String) =
    "zflow_idx_" + s
}
