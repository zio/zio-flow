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

package zio.flow.cassandra

import zio.ZLayer
import zio.flow.cassandra.CassandraTestContainerSupport._
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.test.{TestEnvironment, ZIOSpec, testEnvironment}

trait CassandraSpec extends ZIOSpec[TestEnvironment with CassandraV3 with CassandraV4 with ScyllaDb] {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment with CassandraV3 with CassandraV4 with ScyllaDb] =
    testEnvironment ++ Slf4jBridge.initialize ++ cassandraV3 ++ cassandraV4 ++ scyllaDb
}
