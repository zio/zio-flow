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

package zio.flow.rocksdb

import org.{rocksdb => jrocks}
import zio.flow.internal.RocksDbIndexedStore
import zio.rocksdb.TransactionDB
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.test.Assertion.{containsString, equalTo}
import zio.test.TestAspect.flaky
import zio.test._
import zio._
import zio.flow.internal.IndexedStore.Index
import zio.nio.file.Files

object RocksDbIndexedStoreSpec extends ZIOSpecDefault {
  private val transactionDbLayer = {
    ZLayer
      .scoped(for {
        dir <- Files.createTempDirectoryScoped(Some("zio-rocksdb"), Seq())
        db <- {
          TransactionDB.Live.open(
            new jrocks.Options().setCreateIfMissing(true),
            dir.toString
          )
        }
      } yield db)
  }

  private val diStore: ZLayer[TransactionDB, Throwable, RocksDbIndexedStore] = RocksDbIndexedStore.layer
  private val diStore2: ZLayer[TransactionDB, Throwable, RocksDbIndexedStore] =
    RocksDbIndexedStore.withEmptyTopic("someTopic")
  private val customLayer: ZLayer[Any, Throwable, TransactionDB with RocksDbIndexedStore] =
    transactionDbLayer >+> diStore
  private val customLayer2: ZLayer[Any, Throwable, TransactionDB with RocksDbIndexedStore] =
    transactionDbLayer >+> diStore2

  private val suite1 = suite("RocksDbIndexedStore")(
    test("Test single put") {
      (for {
        diStore   <- ZIO.service[RocksDbIndexedStore]
        insertPos <- diStore.put("SomeTopic", Chunk.fromArray("Value1".getBytes()))
      } yield assert(insertPos)(equalTo(1L))).provide(customLayer)
    },
    test("Test sequential put") {
      (for {
        diStore <- ZIO.service[RocksDbIndexedStore]
        posList <- ZIO.foreach((0 until 10).toList)(i =>
                     diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                   )
        _ <- ZIO.debug(posList.mkString(","))
      } yield assert(posList.mkString(","))(equalTo("1,2,3,4,5,6,7,8,9,10"))).provide(customLayer)
    },
    test("Test scan on empty topic") {
      (for {
        diStore      <- ZIO.service[RocksDbIndexedStore]
        scannedChunk <- diStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield assert(resultChunk.toList.mkString(""))(equalTo(""))).provide(customLayer)
    },
    test("Test sequential put and scan") {
      (for {
        diStore <- ZIO.service[RocksDbIndexedStore]
        _ <- ZIO.foreachDiscard((0 until 10).toList) { i =>
               diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
             }
        scannedChunk <- diStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield assert(resultChunk.toList.mkString(","))(
        equalTo("Value0,Value1,Value2,Value3,Value4,Value5,Value6,Value7,Value8,Value9")
      )).provide(customLayer)
    },
    test("Test concurrent put and scan") {
      val resChunk = (for {
        diStore <- ZIO.service[RocksDbIndexedStore]
        _ <- ZIO.foreachParDiscard((0 until 10).toList)(i =>
               diStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
             )
        scannedChunk <- diStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield resultChunk).provide(customLayer)
      assertZIO(resChunk.map(_.size))(equalTo(10)) *>
        assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value9")) *>
        assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value0"))
    } @@ flaky,
    test("Get namespaces") {
      (for {
        diStore <- ZIO.service[RocksDbIndexedStore]
        ns      <- diStore.getNamespaces()
        _       <- ZIO.debug(ns.get(ProtobufCodec.encode(Schema[String])("someTopic")).toString)
      } yield assertTrue(ns.contains(ProtobufCodec.encode(Schema[String])("someTopic"))))
        .provide(customLayer2)
    }
  )

  override def spec = suite1
}
