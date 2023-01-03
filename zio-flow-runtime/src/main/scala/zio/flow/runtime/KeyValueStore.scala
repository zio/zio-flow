/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

package zio.flow.runtime

import zio.stream.ZStream
import zio.{Chunk, IO, Ref, ZIO, ZLayer}

/**
 * Persistent key-value store
 *
 * The keys are organized into namespaces. THe same key can be used in different
 * namespaces to represent different values. Each entry is also associated with
 * numeric timestamp, and the store is designed to be able to find the latest
 * timestamp within given constraints.
 */
trait KeyValueStore {

  /**
   * Put an item to the store
   * @param namespace
   *   Namespace of the keys
   * @param key
   *   Key of the item
   * @param value
   *   Value of the item
   * @param timestamp
   *   Timestamp of the item. If a value with the same key and timestamp already
   *   exists it is going to be overridden.
   * @return
   *   True if the item was stored
   */
  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte], timestamp: Timestamp): IO[Throwable, Boolean]

  /**
   * Get the stored value of a given key with the largest timestamp
   *
   * @param namespace
   *   Namespace of the keys
   * @param key
   *   Key of the item
   * @param before
   *   If specified, the returned item will be the one with the largest
   *   timestamp but not larger than the provided value. Otherwise it takes the
   *   entry with the largest timestamp.
   * @return
   *   The stored value if it was found, or None
   */
  def getLatest(namespace: String, key: Chunk[Byte], before: Option[Timestamp]): IO[Throwable, Option[Chunk[Byte]]]

  /**
   * Get the largest timestamp a given key has value stored with. This is the
   * timestamp of the element that would be returned by [[getLatest]] if its
   * timestamp is not constrained.
   * @param namespace
   *   Namespace of the keys
   * @param key
   *   Key of the item
   * @return
   *   Largest timestamp stored in the key-value store, or None if there isn't
   *   any
   */
  def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[Throwable, Option[Timestamp]]

  /** Gets all the stored timestamps for a given key */
  def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[Any, Throwable, Timestamp]

  /**
   * Get all key-value pairs of the given namespace, using the latest timestamp
   * for each
   * @param namespace
   *   Namespace of the keys
   * @return
   *   A stream of key-value pairs
   */
  def scanAll(namespace: String): ZStream[Any, Throwable, (Chunk[Byte], Chunk[Byte])]

  /**
   * Get all the keys of the given namespace
   *
   * @param namespace
   *   Namespace of the keys
   * @return
   *   A stream of keys
   */
  def scanAllKeys(namespace: String): ZStream[Any, Throwable, Chunk[Byte]]

  /**
   * Deletes all versions of a given key from a given namespace
   *
   * @param namespace
   *   Namespace of the key
   * @param key
   *   Key of the entries to be deleted
   * @param marker
   *   If None, all values with the given key are going to be deleted. If it is
   *   set to a timestamp, only the old values are going to be deleted, such
   *   that [[getLatest]] called with the marker timestamp can still return a
   *   valid element if there was any before calling [[delete]].
   */
  def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): IO[Throwable, Unit]
}

object KeyValueStore {
  val any: ZLayer[KeyValueStore, Nothing, KeyValueStore] = ZLayer.service[KeyValueStore]

  val inMemory: ZLayer[Any, Nothing, KeyValueStore] =
    ZLayer.scoped {
      for {
        namespaces <- Ref.make(Map.empty[String, Map[Chunk[Byte], List[InMemoryKeyValueEntry]]])
        _ <- ZIO.addFinalizer(
               namespaces.get.flatMap { map =>
                 ZIO.logDebug(map.values.map(_.size).sum.toString + " items left in kv store")
               }
             )
      } yield InMemoryKeyValueStore(namespaces)
    }

  def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte],
    timestamp: Timestamp
  ): ZIO[KeyValueStore, Throwable, Boolean] =
    ZIO.serviceWithZIO(
      _.put(namespace, key, value, timestamp)
    )

  def getLatest(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): ZIO[KeyValueStore, Throwable, Option[Chunk[Byte]]] =
    ZIO.serviceWithZIO(
      _.getLatest(namespace, key, before)
    )

  def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[KeyValueStore, Throwable, Timestamp] =
    ZStream.serviceWithStream(_.getAllTimestamps(namespace, key))

  def scanAll(namespace: String): ZStream[KeyValueStore, Throwable, (Chunk[Byte], Chunk[Byte])] =
    ZStream.serviceWithStream(
      _.scanAll(namespace)
    )

  def scanAllKeys(namespace: String): ZStream[KeyValueStore, Throwable, Chunk[Byte]] =
    ZStream.serviceWithStream(_.scanAllKeys(namespace))

  def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): ZIO[KeyValueStore, Throwable, Unit] =
    ZIO.serviceWithZIO(
      _.delete(namespace, key, marker)
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
    ): IO[Throwable, Boolean] =
      namespaces.update { ns =>
        add(ns, namespace, key, value, timestamp)
      }.as(true)

    override def getLatest(
      namespace: String,
      key: Chunk[Byte],
      before: Option[Timestamp]
    ): IO[Throwable, Option[Chunk[Byte]]] =
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

    override def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[Throwable, Option[Timestamp]] =
      namespaces.get.map { ns =>
        ns.get(namespace)
          .flatMap(_.get(key))
          .flatMap {
            case Nil     => None
            case entries => Some(entries.maxBy(_.timestamp.value).timestamp)
          }
      }

    /** Gets all the stored timestamps for a given key */
    override def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[Any, Throwable, Timestamp] =
      ZStream.fromIterableZIO {
        namespaces.get.map { ns =>
          ns.get(namespace)
            .flatMap(_.get(key))
            .map {
              case Nil     => List.empty[Timestamp]
              case entries => entries.map(_.timestamp)
            }
            .getOrElse(List.empty)
        }
      }

    override def scanAll(namespace: String): ZStream[Any, Throwable, (Chunk[Byte], Chunk[Byte])] =
      ZStream.unwrap {
        namespaces.get.map { ns =>
          ns.get(namespace) match {
            case Some(value) =>
              ZStream.fromIterable(value).map { case (key, value) => (key, value.maxBy(_.timestamp.value).data) }
            case None => ZStream.empty
          }
        }
      }

    override def scanAllKeys(namespace: String): ZStream[Any, Throwable, Chunk[Byte]] =
      ZStream.unwrap {
        namespaces.get.map { ns =>
          ns.get(namespace) match {
            case Some(value) =>
              ZStream.fromIterable(value.keys)
            case None => ZStream.empty
          }
        }
      }

    override def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): IO[Throwable, Unit] =
      marker match {
        case Some(markerTimestamp) =>
          namespaces.update { ns =>
            ns.get(namespace) match {
              case Some(data) =>
                val values          = data.getOrElse(key, List.empty)
                val after           = values.takeWhile(_.timestamp > markerTimestamp)
                val remainingValues = values.take(after.length + 1)

                if (remainingValues.isEmpty)
                  ns.updated(namespace, data - key)
                else
                  ns.updated(
                    namespace,
                    data.updated(key, remainingValues)
                  )
              case None => ns
            }
          }
        case None =>
          namespaces.update { ns =>
            ns.get(namespace) match {
              case Some(data) =>
                ns.updated(namespace, data - key)
              case None =>
                ns
            }
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
