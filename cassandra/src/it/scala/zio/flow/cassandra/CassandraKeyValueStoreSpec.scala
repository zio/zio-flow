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

package zio.flow.cassandra

import zio._
import zio.flow.cassandra.CassandraTestContainerSupport._
import zio.flow.test.KeyValueStoreTests
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.test._

object CassandraKeyValueStoreSpec extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Scope, Any, TestEnvironment] =
    testEnvironment ++ Slf4jBridge.initialize

  override def spec: Spec[Environment, Any] =
    suite("CassandraKeyValueStoreSpec")(
      testUsing(cassandraV3, "Cassandra V3"),
      testUsing(cassandraV4, "Cassandra V4"),
      testUsing(scyllaDb, "ScyllaDB")
    )

  private def testUsing(database: SessionLayer, label: String): Spec[TestEnvironment, Any] =
    KeyValueStoreTests(label, initializeDb = ZIO.unit).tests
      .provideLayerShared(database >>> CassandraKeyValueStore.layer)

}
