package zio.flow.dynamodb

import zio.Scope
import zio.flow.dynamodb.DynamoDbSupport.{createIndexedStoreTable, dynamoDbLayer}
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue, assertZIO}
import zio._
import zio.flow.internal.IndexedStore
import zio.flow.internal.IndexedStore.Index
import zio.test.Assertion.{containsString, equalTo}
import zio.test.TestAspect.{nondeterministic, sequential}

object DynamoDbIndexedStoreSpec extends ZIOSpecDefault {
  private val dynamoDbIndexedStore =
    dynamoDbLayer >+> DynamoDbIndexedStore.layer

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("DynamoDbIndexedStore")(
      test("Test single put") {
        ZIO.scoped {
          for {
            _            <- createIndexedStoreTable(DynamoDbIndexedStore.tableName)
            indexedStore <- ZIO.service[IndexedStore]
            insertPos    <- indexedStore.put("SomeTopic", Chunk.fromArray("Value1".getBytes()))
          } yield assertTrue(insertPos == Index(1L))
        }
      },
      test("Test sequential put") {
        ZIO.scoped {
          for {
            _            <- createIndexedStoreTable(DynamoDbIndexedStore.tableName)
            indexedStore <- ZIO.service[IndexedStore]
            posList <- ZIO.foreach((0 until 10).toList)(i =>
                         indexedStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                       )
            _ <- ZIO.debug(posList.mkString(","))
          } yield assertTrue(posList.mkString(",") == "1,2,3,4,5,6,7,8,9,10")
        }
      },
      test("Test scan on empty topic") {
        ZIO.scoped {
          for {
            _            <- createIndexedStoreTable(DynamoDbIndexedStore.tableName)
            indexedStore <- ZIO.service[IndexedStore]
            scannedChunk <- indexedStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
            resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
          } yield assertTrue(resultChunk.toList.mkString("") == "")
        }
      },
      test("Test sequential put and scan") {
        ZIO.scoped {
          for {
            _            <- createIndexedStoreTable(DynamoDbIndexedStore.tableName)
            indexedStore <- ZIO.service[IndexedStore]
            _ <- ZIO.foreachDiscard((0 until 10).toList) { i =>
                   indexedStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                 }
            scannedChunk <- indexedStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
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
              _            <- createIndexedStoreTable(DynamoDbIndexedStore.tableName)
              indexedStore <- ZIO.service[IndexedStore]
              _ <- ZIO.foreachParDiscard((0 until 10).toList)(i =>
                     indexedStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                   )
              scannedChunk <- indexedStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
              resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
            } yield resultChunk
          }
        assertZIO(resChunk.map(_.size))(equalTo(10)) *>
          assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value9")) *>
          assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value0"))
      }
    ).provideCustomLayerShared(dynamoDbIndexedStore) @@ nondeterministic @@ sequential
}
