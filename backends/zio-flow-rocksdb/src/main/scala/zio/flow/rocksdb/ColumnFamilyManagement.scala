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

import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle}
import zio.rocksdb.TransactionDB
import zio.stm.{TMap, ZSTM}
import zio.{IO, Promise}

import java.nio.charset.StandardCharsets

trait ColumnFamilyManagement {
  def rocksDB: TransactionDB
  def namespaces: TMap[String, Promise[Throwable, ColumnFamilyHandle]]

  protected def getOrCreateNamespace(namespace: String): IO[Throwable, ColumnFamilyHandle] =
    Promise.make[Throwable, ColumnFamilyHandle].flatMap { newPromise =>
      namespaces
        .get(namespace)
        .flatMap {
          case Some(promise) =>
            ZSTM.succeed(promise.await)
          case None =>
            namespaces
              .put(namespace, newPromise)
              .as(
                rocksDB
                  .createColumnFamily(
                    new ColumnFamilyDescriptor(namespace.getBytes(StandardCharsets.UTF_8))
                  )
                  .tapBoth(error => newPromise.fail(error), handle => newPromise.succeed(handle))
              )
        }
        .commit
        .flatten
    }
}
