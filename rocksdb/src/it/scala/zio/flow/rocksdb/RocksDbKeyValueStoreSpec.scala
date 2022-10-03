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

import zio.{ZIO, ZLayer}
import zio.flow.test.KeyValueStoreTests
import zio.nio.file.Files
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault}

object RocksDbKeyValueStoreSpec extends ZIOSpecDefault {

  private val config = ZLayer.scoped {
    Files.createTempDirectoryScoped(Some("zio-rocksdb"), Seq()).map { path =>
      RocksDbConfig(path.toFile.toPath)
    }
  }

  private val kVStoreLayer = config >>> RocksDbKeyValueStore.layer

  def spec: Spec[TestEnvironment, Any] =
    KeyValueStoreTests("RocksDbKeyValueStoreSpec", initializeDb = ZIO.unit).tests
      .provideSome[TestEnvironment](kVStoreLayer)

}
