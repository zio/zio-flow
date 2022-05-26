// TODO
//package zio.flow.dynamodb
//
//import zio.aws.core.AwsError
//import zio.aws.dynamodb.DynamoDb
//import zio.aws.dynamodb.model.primitives._
//import zio.aws.dynamodb.model.{
//  AttributeValue,
//  DeleteItemRequest,
//  GetItemRequest,
//  Put,
//  ScanRequest,
//  TransactWriteItem,
//  TransactWriteItemsRequest,
//  UpdateItemRequest
//}
//import DynamoDbKeyValueStore._
//import zio.flow.internal.KeyValueStore
//import zio.stream.ZStream
//import zio.{Chunk, IO, URLayer, ZIO, ZLayer}
//
//import java.io.IOException
//
//final class DynamoDbKeyValueStore(dynamoDB: DynamoDb) extends KeyValueStore {
//
//  override def put(
//    namespace: String,
//    key: Chunk[Byte],
//    value: Chunk[Byte]
//  ): IO[IOException, Boolean] = {
//
//    val request =
//      UpdateItemRequest(
//        tableName,
//        dynamoDbKey(namespace, key),
//        updateExpression = Option(
//          UpdateExpression(
//            s"SET $valueColumnName = ${RequestExpressionPlaceholder.valueColumn}"
//          )
//        ),
//        expressionAttributeValues = Option(
//          Map(
//            ExpressionAttributeValueVariable(RequestExpressionPlaceholder.valueColumn) -> binaryValue(value)
//          )
//        )
//      )
//
//    dynamoDB
//      .updateItem(request)
//      .mapBoth(
//        ioExceptionOf(s"Error putting key-value pair for <$namespace> namespace", _),
//        _ => true
//      )
//  }
//
//  override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] = {
//
//    val request =
//      GetItemRequest(
//        tableName,
//        dynamoDbKey(namespace, key),
//        projectionExpression = Option(
//          ProjectionExpression(valueColumnName)
//        )
//      )
//
//    dynamoDB
//      .getItem(request)
//      .mapBoth(
//        ioExceptionOf(s"Error retrieving or reading value for <$namespace> namespace", _),
//        result =>
//          for {
//            item      <- result.item.toOption
//            value     <- item.get(AttributeName(valueColumnName))
//            byteChunk <- value.b.toOption
//          } yield byteChunk
//      )
//  }
//
//  override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] = {
//
//    val request = scanRequest.copy(
//      expressionAttributeValues = Option(
//        Map(
//          ExpressionAttributeValueVariable(RequestExpressionPlaceholder.namespaceColumn) -> stringValue(namespace)
//        )
//      )
//    )
//
//    dynamoDB
//      .scan(request)
//      .mapBoth(
//        ioExceptionOf(s"Error scanning all key-value pairs for <$namespace> namespace", _),
//        item =>
//          for {
//            key            <- item.get(AttributeName(keyColumnName))
//            keyByteChunk   <- key.b.toOption
//            value          <- item.get(AttributeName(valueColumnName))
//            valueByteChunk <- value.b.toOption
//          } yield {
//            keyByteChunk -> valueByteChunk
//          }
//      )
//      .collect { case Some(byteChunkPair) =>
//        byteChunkPair
//      }
//  }
//
//  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
//    dynamoDB
//      .deleteItem(
//        DeleteItemRequest(
//          tableName = tableName,
//          key = dynamoDbKey(namespace, key)
//        )
//      )
//      .mapBoth(
//        ioExceptionOf(s"Error deleting item from <$namespace> namespace", _),
//        _ => ()
//      )
//
//  override def putAll(items: Chunk[KeyValueStore.Item]): IO[IOException, Unit] =
//    dynamoDB
//      .transactWriteItems(
//        TransactWriteItemsRequest(
//          transactItems = items.map { item =>
//            TransactWriteItem(
//              put = Put(
//                tableName = tableName,
//                item = dynamoDbKey(item.namespace, item.key) +
//                  (AttributeName(valueColumnName) -> binaryValue(item.value))
//              )
//            )
//          }
//        )
//      )
//      .mapBoth(
//        ioExceptionOf(s"Error putting multiple items transactionally", _),
//        _ => ()
//      )
//}
//
//object DynamoDbKeyValueStore {
//  val layer: URLayer[DynamoDb, KeyValueStore] =
//    ZLayer {
//      ZIO
//        .service[DynamoDb]
//        .map(new DynamoDbKeyValueStore(_))
//    }
//
//  private[dynamodb] val tableName: TableName = TableName("_zflow_key_value_store")
//// TODO
//
//  private[dynamodb] val namespaceColumnName: String =
//    withColumnPrefix("namespace")
//
//  private[dynamodb] val keyColumnName: String =
//    withColumnPrefix("key")
//
//  private[dynamodb] val valueColumnName: String =
//    withColumnPrefix("value")
//
//  private object RequestExpressionPlaceholder {
//    val namespaceColumn: String = ":namespaceValue"
//    val valueColumn: String     = ":valueColumnValue"
//  }
//
//  private val scanRequest: ScanRequest =
//    ScanRequest(
//      tableName,
//      projectionExpression = Option(
//        ProjectionExpression(
//          Seq(keyColumnName, valueColumnName).mkString(", ")
//        )
//      ),
//      filterExpression = Option(
//        ConditionExpression(
//          s"$namespaceColumnName = ${RequestExpressionPlaceholder.namespaceColumn}"
//        )
//      )
//    )
//
//  private def binaryValue(b: Chunk[Byte]): AttributeValue =
//    AttributeValue(
//      b = Option(BinaryAttributeValue(b))
//    )
//
//  private def stringValue(s: String): AttributeValue =
//    AttributeValue(
//      s = Option(StringAttributeValue(s))
//    )
//
//  private def dynamoDbKey(namespace: String, key: Chunk[Byte]): Map[AttributeName, AttributeValue] =
//    Map(
//      AttributeName(namespaceColumnName) -> stringValue(namespace),
//      AttributeName(keyColumnName)       -> binaryValue(key)
//    )
//
//  private def ioExceptionOf(errorContext: String, awsError: AwsError): IOException = {
//    val error = awsError.toThrowable
//
//    new IOException(s"$errorContext: <${error.getMessage}>.", error)
//  }
//
//  private def withColumnPrefix(s: String) =
//    "zflow_kv_" + s
//}
