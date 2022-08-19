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

package zio.flow.internal

import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle}
import zio.rocksdb.{RocksDB, TransactionDB}
import zio.stm.{TMap, ZSTM}
import zio.{IO, Promise, ZIO}

import java.io.IOException
import java.nio.charset.StandardCharsets

trait ColumnFamilyManagement {
  def rocksDB: TransactionDB
  def namespaces: TMap[String, Promise[IOException, ColumnFamilyHandle]]

  protected def getOrCreateNamespace(namespace: String): IO[IOException, ColumnFamilyHandle] =
    Promise.make[IOException, ColumnFamilyHandle].flatMap { newPromise =>
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
                  .refineToOrDie[IOException]
                  .tapBoth(error => newPromise.fail(error), handle => newPromise.succeed(handle))
              )
        }
        .commit
        .flatten
    }
}

object ColumnFamilyManagement {
  private[internal] def getExistingNamespaces(
    rocksDB: RocksDB
  ): IO[IOException, List[(String, Promise[IOException, ColumnFamilyHandle])]] =
    rocksDB.initialHandles.flatMap { handles =>
      ZIO.foreach(handles) { handle =>
        val name = new String(handle.getName, StandardCharsets.UTF_8)
        Promise.make[IOException, ColumnFamilyHandle].flatMap { promise =>
          promise.succeed(handle).as(name -> promise)
        }
      }
    }.refineToOrDie[IOException]
}
