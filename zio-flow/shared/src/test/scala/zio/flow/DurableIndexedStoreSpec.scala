package zio.flow

import org.rocksdb.Options
import zio.console.Console.Service.live.putStrLn
import zio.flow.internal.{DurableIndexedStore, ManagedPath}
import zio.rocksdb.{TransactionDB, service}
import zio.test.Assertion.equalTo
import zio.test.{DefaultRunnableSpec, assert}
import zio.{Chunk, Has, ZIO, ZLayer}

object DurableIndexedStoreSpec extends DefaultRunnableSpec {
  val suite1 = suite("DurableIndexedStore")(testM("Test single put") {
    (for {
      diStore <- ZIO.service[DurableIndexedStore]
      insertPos <- diStore.put("SomeTopic", Chunk.fromArray("Value1".getBytes()))
    } yield assert(insertPos)(equalTo(1L))).provideCustomLayer(customLayer)
  },
    testM("Test sequential put") {
      (for {
      diStore <- ZIO.service[DurableIndexedStore]
      posList <- ZIO.foreach((0 until 10).toList)(i => diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes())))
      _ <- putStrLn(posList.mkString(","))
      } yield assert(posList.mkString(","))(equalTo("1,2,3,4,5,6,7,8,9,10"))).provideCustomLayer(customLayer)
    },
    testM("Test scan on empty topic"){
      (for {
      diStore <- ZIO.service[DurableIndexedStore]
      scannedChunk <- diStore.scan("SomeTopic", 1L, 10L).runCollect
      resultChunk <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      }yield assert(resultChunk.toList.mkString(""))(equalTo("")) ).provideCustomLayer(customLayer)
    },
    testM("Test sequential put and scan") {
      (for {
        diStore <- ZIO.service[DurableIndexedStore]
        _ <- ZIO.foreach((0 until 10).toList)(i => diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes())))
        scannedChunk <- diStore.scan("SomeTopic", 1L, 10L).runCollect
        resultChunk <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      }yield assert(resultChunk.toList.mkString(","))(equalTo("Value0,Value1,Value2,Value3,Value4,Value5,Value6,Value7,Value8,Value9")) ).provideCustomLayer(customLayer)
    },
    testM("Test concurrent put and scan") {
      (for {
        diStore <- ZIO.service[DurableIndexedStore]
        _ <- ZIO.foreachPar((0 until 10).toList)(i => diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes())))
        scannedChunk <- diStore.scan("SomeTopic", 1L, 10L).runCollect
        resultChunk <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      }yield assert(resultChunk.toList.mkString(","))(equalTo("Value0,Value1,Value2,Value3,Value4,Value5,Value6,Value7,Value8,Value9")) ).provideCustomLayer(customLayer)
    })

  //val TOPIC = "SomeTopic"
  val dbPath = "zioflow_rocksdb"
  val db: ZLayer[Any, Throwable, Has[service.TransactionDB]] = {
    ManagedPath()
      .flatMap(dir => TransactionDB.Live.open(new Options().setCreateIfMissing(true), dir.toAbsolutePath.toString))
      .toLayer
  }
  val diStore: ZLayer[Has[service.TransactionDB], Throwable, Has[DurableIndexedStore]] = DurableIndexedStore.live
  val customLayer: ZLayer[Any, Throwable, TransactionDB with Has[DurableIndexedStore]] = db >+> diStore

  override def spec = suite1
}
