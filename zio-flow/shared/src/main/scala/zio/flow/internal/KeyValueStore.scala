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

import java.io.IOException
import org.rocksdb.ColumnFamilyHandle
import zio.rocksdb._
import zio.{Chunk, IO, UIO, ZIO, ZLayer, ZRef}
import zio.stream.ZStream

trait KeyValueStore {

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean]

  def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]]

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])]
}

object KeyValueStore {

  val live: ZLayer[RocksDB, IOException, KeyValueStore] =
    ZLayer {
      for {
        rocksDB    <- ZIO.service[RocksDB]
        namespaces <- getNamespaces(rocksDB)
      } yield {
        KeyValueStoreLive(rocksDB, namespaces)
      }
    }

  val inMemory: ZLayer[Any, Nothing, KeyValueStore] =
    ZLayer {
      for {
        namespaces <- ZRef.make(Map.empty[String, Map[Chunk[Byte], Chunk[Byte]]])
      } yield InMemoryKeyValueStore(namespaces)
    }

  def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte]
  ): ZIO[KeyValueStore, IOException, Boolean] =
    ZIO.serviceWithZIO(
      _.put(namespace, key, value)
    )

  def get(namespace: String, key: Chunk[Byte]): ZIO[KeyValueStore, IOException, Option[Chunk[Byte]]] =
    ZIO.serviceWithZIO(
      _.get(namespace, key)
    )

  def scanAll(namespace: String): ZStream[KeyValueStore, IOException, (Chunk[Byte], Chunk[Byte])] =
    ZStream.serviceWithStream(
      _.scanAll(namespace)
    )

  private def getNamespaces(rocksDB: RocksDB): IO[IOException, Map[Chunk[Byte], ColumnFamilyHandle]] =
    rocksDB.initialHandles
      .map(_.map { namespace =>
        Chunk.fromArray(namespace.getName) -> namespace
      }.toMap)
      .refineToOrDie[IOException]

  private final case class KeyValueStoreLive(
    rocksDB: RocksDB,
    namespaces: Map[Chunk[Byte], ColumnFamilyHandle]
  ) extends KeyValueStore {

    def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
      for {
        namespace <- getNamespace(namespace)
        _         <- rocksDB.put(namespace, key.toArray, value.toArray).refineToOrDie[IOException]
      } yield true

    def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] =
      for {
        namespace <- getNamespace(namespace)
        value     <- rocksDB.get(namespace, key.toArray).refineToOrDie[IOException]
      } yield value.map(Chunk.fromArray)

    def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
      ZStream.unwrap {
        getNamespace(namespace).map { namespace =>
          rocksDB
            .newIterator(namespace)
            .map { case (key, value) => Chunk.fromArray(key) -> Chunk.fromArray(value) }
            .refineToOrDie[IOException]
        }
      }

    def getNamespace(namespace: String): UIO[ColumnFamilyHandle] =
      ZIO.succeed(namespaces(Chunk.fromArray(namespace.getBytes)))
  }

  private final case class InMemoryKeyValueStore(namespaces: zio.Ref[Map[String, Map[Chunk[Byte], Chunk[Byte]]]])
      extends KeyValueStore {
    override def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
      namespaces.update { ns =>
        ns.get(namespace) match {
          case Some(data) =>
            ns.updated(namespace, data.updated(key, value))
          case None =>
            ns + (namespace -> Map(key -> value))
        }
      }.as(true)

    override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] =
      namespaces.get.map { ns =>
        ns.get(namespace).flatMap(_.get(key))
      }

    override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
      ZStream.unwrap {
        namespaces.get.map { ns =>
          ns.get(namespace) match {
            case Some(value) => ZStream.fromIterable(value)
            case None        => ZStream.empty
          }
        }
      }
  }
}
