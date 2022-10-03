package zio.flow.dynamodb

import zio.aws.dynamodb.DynamoDb
import zio.{Scope, _}
import zio.flow.dynamodb.DynamoDbSupport.{createIndexedStoreTable, dynamoDbLayer}
import zio.flow.test.IndexedStoreTests
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, testEnvironment}

object DynamoDbIndexedStoreSpec extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Slf4jBridge.initialize

  private val dynamoDbIndexedStore =
    dynamoDbLayer >+> DynamoDbIndexedStore.layer

  override def spec: Spec[TestEnvironment with Scope, Any] =
    IndexedStoreTests[DynamoDb](
      "DynamoDbIndexedStore",
      initializeDb = createIndexedStoreTable(DynamoDbIndexedStore.tableName)
    ).tests.provideSomeLayerShared[TestEnvironment](dynamoDbIndexedStore)
}
