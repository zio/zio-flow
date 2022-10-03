package zio.flow.cassandra

import zio._
import zio.flow.cassandra.CassandraTestContainerSupport._
import zio.flow.test.IndexedStoreTests
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.test._

object CassandraIndexedStoreSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Slf4jBridge.initialize

  override def spec: Spec[Environment, Any] =
    suite("CassandraIndexedStoreSpec")(
      testUsing(cassandraV3, "Cassandra V3"),
      testUsing(cassandraV4, "Cassandra V4"),
      testUsing(scyllaDb, "ScyllaDB")
    )

  private def testUsing(database: SessionLayer, label: String): Spec[TestEnvironment, Any] =
    IndexedStoreTests(label, initializeDb = ZIO.unit).tests
      .provideSomeLayerShared[TestEnvironment](database >>> CassandraIndexedStore.layer)
}
