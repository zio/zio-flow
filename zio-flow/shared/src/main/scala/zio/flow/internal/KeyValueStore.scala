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

import zio.flow.internal.KeyValueStore.Item
import zio.stream.ZStream
import zio.{Chunk, IO, Ref, ZIO, ZLayer}

import java.io.IOException

trait KeyValueStore {

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean]

  def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]]

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])]

  def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit]

  def putAll(items: Chunk[Item]): IO[IOException, Unit]
}

object KeyValueStore {
  final case class Item(namespace: String, key: Chunk[Byte], value: Chunk[Byte])

  val inMemory: ZLayer[Any, Nothing, KeyValueStore] =
    ZLayer {
      for {
        namespaces <- Ref.make(Map.empty[String, Map[Chunk[Byte], Chunk[Byte]]])
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

  private final case class InMemoryKeyValueStore(namespaces: zio.Ref[Map[String, Map[Chunk[Byte], Chunk[Byte]]]])
      extends KeyValueStore {
    override def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
      namespaces.update { ns =>
        add(ns, Item(namespace, key, value))
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

    override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
      namespaces.update { ns =>
        ns.get(namespace) match {
          case Some(data) =>
            ns.updated(namespace, data - key)
          case None =>
            ns
        }
      }

    override def putAll(items: Chunk[Item]): IO[IOException, Unit] =
      namespaces.update { ns =>
        items.foldLeft(ns)(add)
      }

    private def add(
      ns: Map[String, Map[Chunk[Byte], Chunk[Byte]]],
      item: Item
    ): Map[String, Map[Chunk[Byte], Chunk[Byte]]] =
      ns.get(item.namespace) match {
        case Some(data) =>
          ns.updated(item.namespace, data.updated(item.key, item.value))
        case None =>
          ns + (item.namespace -> Map(item.key -> item.value))
      }
  }
}
