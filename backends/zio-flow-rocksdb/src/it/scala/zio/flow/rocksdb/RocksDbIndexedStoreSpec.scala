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
import zio.flow.runtime.IndexedStore
import zio.flow.runtime.test.IndexedStoreTests
import zio.nio.file.{Files, Path}
import zio.rocksdb.TransactionDB
import zio.test._

import java.io.IOException
import java.nio.charset.StandardCharsets

object RocksDbIndexedStoreSpec extends ZIOSpecDefault {
  private val transactionDbPath: ZLayer[Any, IOException, Path] =
    ZLayer.scoped {
      Files.createTempDirectoryScoped(Some("zio-rocksdb"), Seq())
    }

  private val config: ZLayer[Path, Nothing, Unit] = ZLayer.scoped {
    ZIO.service[Path].flatMap { path =>
      DefaultServices.currentServices.locallyScopedWith(
        _.add(
          ConfigProvider.fromMap(
            Map("rocksdb-indexed-store.path" -> path.toFile.toPath.toString)
          )
        )
      )
    }
  }

  private val testIndexedStore: ZLayer[Any, Throwable, IndexedStore] = RocksDbIndexedStore.layer

  override def spec: Spec[TestEnvironment, Throwable] =
    suite("RocksDbIndexedStore")(
      IndexedStoreTests("Common", initializeDb = ZIO.unit).tests
        .provideSome[TestEnvironment](testIndexedStore)
        .provideSome[TestEnvironment](transactionDbPath, config),
      test("Get namespaces") {
        (for {
          path <- ZIO.service[Path]
          _ <-
            ZIO
              .service[IndexedStore]
              .provide(ZLayer.succeed(path) >>> config >>> RocksDbIndexedStore.withEmptyTopic("someTopic"))
          ns <-
            ZIO
              .serviceWithZIO[TransactionDB](_.initialHandles)
              .map(_.map(handle => new String(handle.getName, StandardCharsets.UTF_8)))
              .provide(
                TransactionDB.liveAllColumnFamilies(
                  new jrocksdb.DBOptions(),
                  new jrocksdb.ColumnFamilyOptions(),
                  new jrocksdb.TransactionDBOptions(),
                  path.toString
                )
              ) // Needs to reopen the same DB
        } yield assertTrue(ns.contains("someTopic"))).provide(transactionDbPath)
      }
    )
}
