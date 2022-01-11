package zio.flow

import org.rocksdb.Options
import zio.console.Console.Service.live.putStrLn
import zio.flow.internal.{DurableIndexedStore, ManagedPath}
import zio.rocksdb.{TransactionDB, service}
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.test.Assertion.{containsString, equalTo}
import zio.test.TestAspect.flaky
import zio.test.{DefaultRunnableSpec, assert, assertM, assertTrue}
import zio.{Chunk, Has, ZIO, ZLayer}

object DurableIndexedStoreSpec extends DefaultRunnableSpec {
  val suite1 = suite("DurableIndexedStore")(
    testM("Test single put") {
      (for {
        diStore   <- ZIO.service[DurableIndexedStore]
        insertPos <- diStore.put("SomeTopic", Chunk.fromArray("Value1".getBytes()))
      } yield assert(insertPos)(equalTo(1L))).provideCustomLayer(customLayer)
    },
    testM("Test sequential put") {
      (for {
        diStore <- ZIO.service[DurableIndexedStore]
        posList <- ZIO.foreach((0 until 10).toList)(i =>
                     diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                   )
        _ <- putStrLn(posList.mkString(","))
      } yield assert(posList.mkString(","))(equalTo("1,2,3,4,5,6,7,8,9,10"))).provideCustomLayer(customLayer)
    },
    testM("Test scan on empty topic") {
      (for {
        diStore      <- ZIO.service[DurableIndexedStore]
        scannedChunk <- diStore.scan("SomeTopic", 1L, 10L).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield assert(resultChunk.toList.mkString(""))(equalTo(""))).provideCustomLayer(customLayer)
    },
    testM("Test sequential put and scan") {
      (for {
        diStore <- ZIO.service[DurableIndexedStore]
        _ <- ZIO.foreach((0 until 10).toList)(i =>
               diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
             )
        scannedChunk <- diStore.scan("SomeTopic", 1L, 10L).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield assert(resultChunk.toList.mkString(","))(
        equalTo("Value0,Value1,Value2,Value3,Value4,Value5,Value6,Value7,Value8,Value9")
      )).provideCustomLayer(customLayer)
    },
    testM("Test concurrent put and scan") {
      val resChunk = (for {
        diStore <- ZIO.service[DurableIndexedStore]
        _ <- ZIO.foreachPar((0 until 10).toList)(i =>
               diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
             )
        scannedChunk <- diStore.scan("SomeTopic", 1L, 10L).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield resultChunk).provideCustomLayer(customLayer)
      assertM(resChunk.map(_.size))(equalTo(10)) *>
        assertM(resChunk.map(_.toList.mkString(",")))(containsString("Value9")) *>
        assertM(resChunk.map(_.toList.mkString(",")))(containsString("Value0"))
    } @@ flaky,
    testM("Get namespaces") {
      (for {
        diStore <- ZIO.service[DurableIndexedStore]
        ns      <- diStore.getNamespaces()
        _       <- putStrLn(ns.get(ProtobufCodec.encode(Schema[String])("someTopic")).toString)
      } yield assertTrue(ns.contains(ProtobufCodec.encode(Schema[String])("someTopic"))))
        .provideCustomLayer(customLayer2)
    }
  )

  //val TOPIC = "SomeTopic"
  val dbPath = "zioflow_rocksdb"
  val db: ZLayer[Any, Throwable, Has[service.TransactionDB]] = {
    ManagedPath()
      .flatMap(dir => TransactionDB.Live.open(new Options().setCreateIfMissing(true), dir.toAbsolutePath.toString))
      .toLayer
  }
  val diStore: ZLayer[Has[service.TransactionDB], Throwable, Has[DurableIndexedStore]] = DurableIndexedStore.live
  val diStore2: ZLayer[Has[service.TransactionDB], Throwable, Has[DurableIndexedStore]] =
    DurableIndexedStore.live("someTopic")
  val customLayer: ZLayer[Any, Throwable, TransactionDB with Has[DurableIndexedStore]]               = db >+> diStore
  val customLayer2: ZLayer[Any, Throwable, Has[service.TransactionDB] with Has[DurableIndexedStore]] = db >+> diStore2

  override def spec = suite1
}
