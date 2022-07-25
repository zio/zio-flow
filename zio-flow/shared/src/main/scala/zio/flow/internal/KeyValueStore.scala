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

import zio.stream.ZStream
import zio.{Chunk, IO, Ref, ZIO, ZLayer}

import java.io.IOException

trait KeyValueStore {

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte], timestamp: Timestamp): IO[IOException, Boolean]

  def getLatest(namespace: String, key: Chunk[Byte], before: Option[Timestamp]): IO[IOException, Option[Chunk[Byte]]]

  def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Timestamp]]

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])]

  def scanAllKeys(namespace: String): ZStream[Any, IOException, Chunk[Byte]]

  def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit]
}

object KeyValueStore {
  val inMemory: ZLayer[Any, Nothing, KeyValueStore] =
    ZLayer.scoped {
      for {
        namespaces <- Ref.make(Map.empty[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]])
        _ <- ZIO.addFinalizer(
               namespaces.get.flatMap { map =>
                 ZIO.debug(map.values.map(_.size).sum.toString + " items left in kv store")
               }
             )
      } yield InMemoryKeyValueStore(namespaces)
    }

  def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte],
    timestamp: Timestamp
  ): ZIO[KeyValueStore, IOException, Boolean] =
    ZIO.serviceWithZIO(
      _.put(namespace, key, value, timestamp)
    )

  def getLatest(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): ZIO[KeyValueStore, IOException, Option[Chunk[Byte]]] =
    ZIO.serviceWithZIO(
      _.getLatest(namespace, key, before)
    )

  def scanAll(namespace: String): ZStream[KeyValueStore, IOException, (Chunk[Byte], Chunk[Byte])] =
    ZStream.serviceWithStream(
      _.scanAll(namespace)
    )

  def scanAllKeys(namespace: String): ZStream[KeyValueStore, IOException, Chunk[Byte]] =
    ZStream.serviceWithStream(_.scanAllKeys(namespace))

  def delete(namespace: String, key: Chunk[Byte]): ZIO[KeyValueStore, IOException, Unit] =
    ZIO.serviceWithZIO(
      _.delete(namespace, key)
    )

  private final case class InMemoryKeyValueEntry(data: Chunk[Byte], timestamp: Timestamp) {
    override def toString: String = s"<entry ($data.size bytes) at $timestamp>"
  }

  private final case class InMemoryKeyValueStore(
    namespaces: zio.Ref[Map[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]]]
  ) extends KeyValueStore {
    override def put(
      namespace: String,
      key: Chunk[Byte],
      value: Chunk[Byte],
      timestamp: Timestamp
    ): IO[IOException, Boolean] =
//      ZIO.logDebug(
//        s"KVSTORE PUT [$timestamp] [$namespace] ${new String(key.toArray)}"
//      ) *>
      namespaces.update { ns =>
        add(ns, namespace, key, value, timestamp)
      }.as(true)

    override def getLatest(
      namespace: String,
      key: Chunk[Byte],
      before: Option[Timestamp]
    ): IO[IOException, Option[Chunk[Byte]]] =
      namespaces.get.map { ns =>
        ns.get(namespace)
          .flatMap(_.get(key))
          .flatMap(
            _.filter(_.timestamp <= before.getOrElse(Timestamp(Long.MaxValue))) match {
              case Nil => None
              case filtered =>
                Some(filtered.maxBy(_.timestamp.value).data)
            }
          )
      }
//      }.tap(_ =>
//        ZIO
//          .logDebug(s"KVSTORE GET LATEST [$before] [$namespace] ${new String(key.toArray)}")
//      )

    override def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Timestamp]] =
      namespaces.get.map { ns =>
        ns.get(namespace)
          .flatMap(_.get(key))
          .flatMap {
            case Nil     => None
            case entries => Some(entries.maxBy(_.timestamp.value).timestamp)
          }
      }
//        .tap(result =>
//        ZIO
//          .logDebug(s"KVSTORE GET LATEST TIMESTAMP [$namespace] ${new String(key.toArray)} => $result")
//      )

    override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
      ZStream.unwrap {
        namespaces.get.map { ns =>
          ns.get(namespace) match {
            case Some(value) =>
              ZStream.fromIterable(value).map { case (key, value) => (key, value.maxBy(_.timestamp.value).data) }
            case None => ZStream.empty
          }
        }
      }

    override def scanAllKeys(namespace: String): ZStream[Any, IOException, Chunk[Byte]] =
      ZStream.unwrap {
        namespaces.get.map { ns =>
          ns.get(namespace) match {
            case Some(value) =>
              ZStream.fromIterable(value.keys)
            case None => ZStream.empty
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

    private def add(
      ns: Map[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]],
      namespace: String,
      key: Chunk[Byte],
      value: Chunk[Byte],
      timestamp: Timestamp
    ): Map[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]] =
      ns.get(namespace) match {
        case Some(data) =>
          data.get(key) match {
            case Some(entries) =>
              ns.updated(
                namespace,
                data.updated(
                  key,
                  InMemoryKeyValueEntry(
                    value,
                    timestamp
                  ) :: entries
                )
              )
            case None =>
              ns.updated(
                namespace,
                data.updated(key, List(InMemoryKeyValueEntry(value, timestamp)))
              )
          }
        case None =>
          ns + (namespace -> Map(key -> List(InMemoryKeyValueEntry(value, timestamp))))
      }

    override def toString: String = "InMemoryKeyValueStore"
  }
}
