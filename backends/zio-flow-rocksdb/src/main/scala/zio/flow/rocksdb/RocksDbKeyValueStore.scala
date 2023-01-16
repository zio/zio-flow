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

package zio.flow.rocksdb

import org.rocksdb.ColumnFamilyHandle
import zio.constraintless.TypeList.{::, End}
import zio.flow.rocksdb.RocksDbKeyValueStore.codecs
import zio.flow.rocksdb.metrics.MeteredTransactionDB
import zio.flow.runtime.{KeyValueStore, Timestamp}
import zio.rocksdb.{Transaction, TransactionDB}
import zio.schema.codec.BinaryCodecs
import zio.schema.codec.ProtobufCodec._
import zio.stm.TMap
import zio.stream.ZStream
import zio.{Chunk, IO, Promise, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

final case class RocksDbKeyValueStore(
  rocksDB: TransactionDB,
  namespaces: TMap[String, Promise[Throwable, ColumnFamilyHandle]]
) extends KeyValueStore
    with ColumnFamilyManagement {

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte],
    timestamp: Timestamp
  ): IO[Throwable, Boolean] =
    for {
      dataNamespace    <- getOrCreateNamespace(dataNamespace(namespace))
      versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
      versionedKey      = getVersionedKey(key, timestamp)
      _ <- rocksDB.atomically {
             for {
               _               <- Transaction.put(dataNamespace, versionedKey.toArray, value.toArray)
               versions        <- Transaction.getForUpdate(versionNamespace, key.toArray, exclusive = true)
               updatedVersions <- appendTimestamp(versions, timestamp)
               _               <- Transaction.put(versionNamespace, key.toArray, updatedVersions.toArray)
             } yield ()
           }
    } yield true

  override def getLatest(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): IO[Throwable, Option[Chunk[Byte]]] =
    for {
      dataNamespace    <- getOrCreateNamespace(dataNamespace(namespace))
      versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
      rawVersions      <- rocksDB.get(versionNamespace, key.toArray)
      lastTimestamp    <- getLastTimestamp(rawVersions, before)
      result <- lastTimestamp match {
                  case Some(lastTimestamp) =>
                    rocksDB
                      .get(dataNamespace, getVersionedKey(key, lastTimestamp).toArray)
                      .map(_.map(Chunk.fromArray))

                  case None => ZIO.none
                }

    } yield result

  override def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[Throwable, Option[Timestamp]] =
    for {
      versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
      rawVersions      <- rocksDB.get(versionNamespace, key.toArray)
      lastTimestamp    <- getLastTimestamp(rawVersions, None)
    } yield lastTimestamp

  /** Gets all the stored timestamps for a given key */
  override def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[Any, Throwable, Timestamp] =
    ZStream.fromIterableZIO {
      for {
        versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
        rawVersions      <- rocksDB.get(versionNamespace, key.toArray)
        versions <- rawVersions match {
                      case Some(value) =>
                        decodeRawVersions(value)
                      case None => ZIO.succeed(List.empty)
                    }
      } yield versions
    }

  def scanAll(namespace: String): ZStream[Any, Throwable, (Chunk[Byte], Chunk[Byte])] =
    ZStream.unwrap {
      for {
        versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
        dataNamespace    <- getOrCreateNamespace(dataNamespace(namespace))
        result = rocksDB
                   .newIterator(versionNamespace)
                   .mapZIO { case (key, value) =>
                     val keyChunk = Chunk.fromArray(key)
                     getLastTimestamp(Some(value), None).flatMap {
                       case None =>
                         ZIO.succeed(Chunk.empty)
                       case Some(timestamp) =>
                         rocksDB
                           .get(dataNamespace, getVersionedKey(keyChunk, timestamp).toArray)
                           .map {
                             case None       => Chunk.empty
                             case Some(data) => Chunk(keyChunk -> Chunk.fromArray(data))
                           }
                     }
                   }
                   .flattenChunks
      } yield result
    }

  def scanAllKeys(namespace: String): ZStream[Any, Throwable, Chunk[Byte]] =
    ZStream.unwrap {
      for {
        versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
        result = rocksDB
                   .newIterator(versionNamespace)
                   .mapZIO { case (key, value) =>
                     val keyChunk = Chunk.fromArray(key)
                     getLastTimestamp(Some(value), None).flatMap {
                       case None =>
                         ZIO.succeed(Chunk.empty)
                       case Some(_) =>
                         ZIO.succeed(Chunk(keyChunk))
                     }
                   }
                   .flattenChunks
      } yield result
    }

  override def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): IO[Throwable, Unit] =
    for {
      dataNamespace    <- getOrCreateNamespace(dataNamespace(namespace))
      versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
      rawVersions      <- rocksDB.get(versionNamespace, key.toArray)
      _ <- rawVersions match {
             case Some(rawVersions) =>
               for {
                 versions <- decodeRawVersions(rawVersions)
                 toDelete = marker match {
                              case Some(markerTimestamp) =>
                                versions.dropWhile(_ > markerTimestamp).drop(1)
                              case None =>
                                versions
                            }
                 _ <-
                   if (versions.size == toDelete.size)
                     rocksDB.delete(versionNamespace, key.toArray)
                   else
                     rocksDB.put(
                       versionNamespace,
                       key.toArray,
                       codecs
                         .encode(versions.take(versions.length - toDelete.length))
                         .toArray
                     )
                 _ <- ZIO.foreachDiscard(toDelete) { timestamp =>
                        rocksDB.delete(dataNamespace, getVersionedKey(key, timestamp).toArray)
                      }
               } yield ()
             case None =>
               ZIO.unit
           }
    } yield ()

  private def getVersionedKey(key: Chunk[Byte], timestamp: Timestamp): Chunk[Byte] =
    key ++ ("_" + timestamp.value.toString).getBytes(StandardCharsets.UTF_8)

  private def appendTimestamp(rawVersions: Option[Array[Byte]], timestamp: Timestamp): IO[Throwable, Chunk[Byte]] =
    rawVersions match {
      case Some(rawVersions) =>
        codecs.decode[List[Timestamp]](Chunk.fromArray(rawVersions)) match {
          case Left(failure) =>
            ZIO.fail(new Throwable(s"Failed to decode versions: $failure"))
          case Right(versions) =>
            val updatedVersions = timestamp :: versions
            ZIO.succeed(codecs.encode(updatedVersions))
        }
      case None =>
        ZIO.succeed(codecs.encode(List(timestamp)))
    }

  private def getLastTimestamp(
    rawVersions: Option[Array[Byte]],
    before: Option[Timestamp]
  ): ZIO[Any, Throwable, Option[Timestamp]] =
    rawVersions match {
      case Some(rawVersions) =>
        decodeRawVersions(rawVersions)
          .map(_.filter(_ <= before.getOrElse(Timestamp(Long.MaxValue))) match {
            case Nil      => None
            case filtered => Some(filtered.maxBy(_.value))
          })
      case None => ZIO.none
    }

  private def decodeRawVersions(rawVersions: Array[Byte]): ZIO[Any, Throwable, List[Timestamp]] =
    codecs.decode[List[Timestamp]](Chunk.fromArray(rawVersions)) match {
      case Left(failure) =>
        ZIO.fail(new Throwable(s"Failed to decode versions: $failure"))
      case Right(versions) =>
        ZIO.succeed(versions)
    }

  private def dataNamespace(namespace: String): String =
    s"data__$namespace"

  private def versionNamespace(namespace: String): String =
    s"version__$namespace"
}

object RocksDbKeyValueStore {
  val layer: ZLayer[Any, Throwable, KeyValueStore] =
    ZLayer.scoped {
      for {
        options <- ZIO.config(RocksDbConfig.config.nested("rocksdb-key-value-store"))
        rocksDb <- TransactionDB.Live.openAllColumnFamilies(
                     options.toDBOptions,
                     options.toColumnFamilyOptions,
                     options.toTransactionDBOptions,
                     options.toJRocksDbPath
                   )
        meteredRocksDb     = MeteredTransactionDB("key-value-store", rocksDb)
        initialNamespaces <- meteredRocksDb.initialHandles
        initialPromiseMap <- ZIO.foreach(initialNamespaces) { handle =>
                               val name = new String(handle.getName, StandardCharsets.UTF_8)
                               Promise.make[Throwable, ColumnFamilyHandle].flatMap { promise =>
                                 promise.succeed(handle).as(name -> promise)
                               }
                             }
        namespaces <- TMap.make[String, Promise[Throwable, ColumnFamilyHandle]](initialPromiseMap: _*).commit
      } yield RocksDbKeyValueStore(meteredRocksDb, namespaces)
    }

  private lazy val codecs = BinaryCodecs.make[List[Timestamp] :: End]
}
