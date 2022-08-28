package zio.flow.cassandra

import CassandraTestContainerSupport._
import zio.test._
import zio._
import zio.flow.internal.IndexedStore
import zio.flow.internal.IndexedStore.Index
import zio.test.Assertion._
import zio.test.TestAspect.{nondeterministic, sequential}

object CassandraIndexedStoreSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment, Any] =
    suite("CassandraIndexedStoreSpec")(
      testUsing(cassandraV3, "Cassandra V3"),
      testUsing(cassandraV4, "Cassandra V4"),
      testUsing(scyllaDb, "ScyllaDB")
    )

  private def testUsing(database: SessionLayer, label: String): Spec[TestEnvironment, Any] =
    suite(label)(
      test("Test single put") {
        ZIO.scoped {
          for {
            indexedStore <- ZIO.service[IndexedStore]
            insertPos    <- indexedStore.put("SomeTopic1", Chunk.fromArray("Value1".getBytes()))
          } yield assertTrue(insertPos == Index(1L))
        }
      },
      test("Test sequential put") {
        ZIO.scoped {
          for {
            indexedStore <- ZIO.service[IndexedStore]
            posList <- ZIO.foreach((0 until 10).toList)(i =>
                         indexedStore.put("SomeTopic2", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                       )
            _ <- ZIO.debug(posList.mkString(","))
          } yield assertTrue(posList.mkString(",") == "1,2,3,4,5,6,7,8,9,10")
        }
      },
      test("Test scan on empty topic") {
        ZIO.scoped {
          for {
            indexedStore <- ZIO.service[IndexedStore]
            scannedChunk <- indexedStore.scan("SomeTopic3", Index(1L), Index(10L)).runCollect
            resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
          } yield assertTrue(resultChunk.toList.mkString("") == "")
        }
      },
      test("Test sequential put and scan") {
        ZIO.scoped {
          for {
            indexedStore <- ZIO.service[IndexedStore]
            _ <- ZIO.foreachDiscard((0 until 10).toList) { i =>
                   indexedStore.put("SomeTopic4", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                 }
            scannedChunk <- indexedStore.scan("SomeTopic4", Index(1L), Index(10L)).runCollect
            resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
          } yield assertTrue(
            resultChunk.toList.mkString(",") == "Value0,Value1,Value2,Value3,Value4,Value5,Value6,Value7,Value8,Value9"
          )
        }
      },
      test("Test concurrent put and scan") {
        val resChunk =
          ZIO.scoped {
            for {
              indexedStore <- ZIO.service[IndexedStore]
              _ <- ZIO.foreachParDiscard((0 until 10).toList)(i =>
                     indexedStore.put("SomeTopic5", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                   )
              scannedChunk <- indexedStore.scan("SomeTopic5", Index(1L), Index(10L)).runCollect
              resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
            } yield resultChunk
          }
        assertZIO(resChunk.map(_.size))(equalTo(10)) *>
          assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value9")) *>
          assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value0"))
      }
    ).provideCustomLayerShared(database >>> CassandraIndexedStore.layer) @@ nondeterministic @@ sequential
}
