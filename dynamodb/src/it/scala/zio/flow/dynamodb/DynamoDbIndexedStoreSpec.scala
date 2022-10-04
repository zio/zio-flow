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
