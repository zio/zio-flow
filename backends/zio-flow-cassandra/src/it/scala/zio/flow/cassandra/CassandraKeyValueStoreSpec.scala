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

import com.datastax.oss.driver.api.core.CqlSession
import zio._
import zio.flow.cassandra.CassandraTestContainerSupport._
import zio.flow.runtime.test.KeyValueStoreTests
import zio.test.TestAspect.sequential
import zio.test._

object CassandraKeyValueStoreSpec extends CassandraSpec {

  override def spec: Spec[Environment, Any] =
    suite("CassandraKeyValueStoreSpec")(
      testUsing(ZIO.service[CassandraV3].map(_.session), "Cassandra V3"),
      testUsing(ZIO.service[CassandraV4].map(_.session), "Cassandra V4"),
      testUsing(ZIO.service[ScyllaDb].map(_.session), "ScyllaDB")
    ) @@ sequential

  private def testUsing[R](database: ZIO[R, Nothing, CqlSession], label: String): Spec[TestEnvironment with R, Any] =
    KeyValueStoreTests(label, initializeDb = ZIO.unit).tests
      .provideSomeLayerShared[TestEnvironment with R](ZLayer(database) >>> CassandraKeyValueStore.fromSession)

}
