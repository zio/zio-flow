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

import java.nio.file.{Path, Paths}
import org.{rocksdb => jrocksdb}
import zio.Config

case class RocksDbConfig(path: Path) {
  def toDBOptions: jrocksdb.DBOptions =
    new jrocksdb.DBOptions()
      .setCreateIfMissing(true)

  def toTransactionDBOptions: jrocksdb.TransactionDBOptions =
    new jrocksdb.TransactionDBOptions()

  def toColumnFamilyOptions: jrocksdb.ColumnFamilyOptions =
    new jrocksdb.ColumnFamilyOptions()

  def toJRocksDbPath: String = path.toString
}

object RocksDbConfig {
  private val path: Config[Path] =
    Config.string.mapAttempt(path => Paths.get(path))

  val config: Config[RocksDbConfig] =
    path.nested("path").map { path =>
      RocksDbConfig(path)
    }
}
