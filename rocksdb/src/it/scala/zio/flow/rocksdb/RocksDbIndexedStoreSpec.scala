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

import org.{rocksdb => jrocksdb}
import zio._
import zio.flow.internal.IndexedStore
import zio.flow.internal.IndexedStore.Index
import zio.nio.file.{Files, Path}
import zio.rocksdb.TransactionDB
import zio.test.Assertion.{containsString, equalTo}
import zio.test.TestAspect.flaky
import zio.test._

import java.io.IOException
import java.nio.charset.StandardCharsets

object RocksDbIndexedStoreSpec extends ZIOSpecDefault {
  private val transactionDbPath: ZLayer[Any, IOException, Path] =
    ZLayer.scoped {
      Files.createTempDirectoryScoped(Some("zio-rocksdb"), Seq())
    }

  private val config: ZLayer[Path, Nothing, RocksDbConfig] = ZLayer.scoped {
    ZIO.service[Path].map { path =>
      RocksDbConfig(path.toFile.toPath)
    }
  }

  private val testIndexedStore: ZLayer[Any, Throwable, IndexedStore] =
    transactionDbPath >>> config >>> RocksDbIndexedStore.layer

  private val suite1 = suite("RocksDbIndexedStore")(
    test("Test single put") {
      (for {
        indexedStore <- ZIO.service[IndexedStore]
        insertPos    <- indexedStore.put("SomeTopic", Chunk.fromArray("Value1".getBytes()))
      } yield assertTrue(insertPos == Index(1L))).provide(testIndexedStore)
    },
    test("Test sequential put") {
      (for {
        indeedStore <- ZIO.service[IndexedStore]
        posList <- ZIO.foreach((0 until 10).toList)(i =>
                     indeedStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                   )
        _ <- ZIO.debug(posList.mkString(","))
      } yield assertTrue(posList.mkString(",") == "1,2,3,4,5,6,7,8,9,10")).provide(testIndexedStore)
    },
    test("Test scan on empty topic") {
      (for {
        indexedStore <- ZIO.service[IndexedStore]
        scannedChunk <- indexedStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield assertTrue(resultChunk.toList.mkString("") == "")).provide(testIndexedStore)
    },
    test("Test sequential put and scan") {
      (for {
        indexedStore <- ZIO.service[IndexedStore]
        _ <- ZIO.foreachDiscard((0 until 10).toList) { i =>
               indexedStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
             }
        scannedChunk <- indexedStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield assertTrue(
        resultChunk.toList.mkString(",") == "Value0,Value1,Value2,Value3,Value4,Value5,Value6,Value7,Value8,Value9"
      )).provide(testIndexedStore)
    },
    test("Test concurrent put and scan") {
      val resChunk = (for {
        indexedStore <- ZIO.service[IndexedStore]
        _ <- ZIO.foreachParDiscard((0 until 10).toList)(i =>
               indexedStore.put("SomeTopic", Chunk.fromArray(s"Value${i.toString}".getBytes()))
             )
        scannedChunk <- indexedStore.scan("SomeTopic", Index(1L), Index(10L)).runCollect
        resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
      } yield resultChunk).provide(testIndexedStore)
      assertZIO(resChunk.map(_.size))(equalTo(10)) *>
        assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value9")) *>
        assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value0"))
    } @@ flaky,
    test("Get namespaces") {
      (for {
        path <- ZIO.service[Path]
        _    <- ZIO.debug(path.toString())
        _ <-
          ZIO
            .service[IndexedStore]
            .provide(ZLayer.succeed(path) >>> config >>> RocksDbIndexedStore.withEmptyTopic("someTopic"))
        handles <-
          ZIO
            .serviceWithZIO[TransactionDB](_.initialHandles)
            .provide(
              TransactionDB.liveAllColumnFamilies(
                new jrocksdb.DBOptions(),
                new jrocksdb.ColumnFamilyOptions(),
                new jrocksdb.TransactionDBOptions(),
                path.toString
              )
            ) // Needs to reopen the same DB
        ns = handles.map(handle => new String(handle.getName, StandardCharsets.UTF_8))
        _ <- ZIO.debug(s"NS: $ns")
      } yield assertTrue(ns.contains("someTopic"))).provide(transactionDbPath)
    }
  )

  override def spec = suite1
}
