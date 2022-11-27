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

import zio.{ConfigProvider, DefaultServices, ZIO, ZLayer}
import zio.flow.runtime.test.KeyValueStoreTests
import zio.nio.file.Files
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault}

import java.io.IOException

object RocksDbKeyValueStoreSpec extends ZIOSpecDefault {

  private val config: ZLayer[Any, IOException, Unit] = ZLayer.scoped {
    Files.createTempDirectoryScoped(Some("zio-rocksdb"), Seq()).flatMap { path =>
      DefaultServices.currentServices.locallyScopedWith(
        _.add(
          ConfigProvider.fromMap(
            Map("rocksdb-key-value-store.path" -> path.toFile.toPath.toString)
          )
        )
      )
    }
  }

  private val kVStoreLayer = RocksDbKeyValueStore.layer

  def spec: Spec[TestEnvironment, Any] =
    KeyValueStoreTests("RocksDbKeyValueStoreSpec", initializeDb = ZIO.unit).tests
      .provideSome[TestEnvironment](kVStoreLayer)
      .provideSome[TestEnvironment](config)

}
