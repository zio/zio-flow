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
import zio.rocksdb.{RocksDB, Transaction, TransactionDB}
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.stm.{TMap, ZSTM}
import zio.stream.ZStream
import zio.{Chunk, IO, Promise, ZIO, ZLayer}

import java.io.IOException
import java.nio.charset.StandardCharsets

final case class RocksDbKeyValueStore(
  rocksDB: TransactionDB,
  namespaces: TMap[String, Promise[IOException, ColumnFamilyHandle]]
) extends KeyValueStore {

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte],
    timestamp: Timestamp
  ): IO[IOException, Boolean] =
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
           }.refineToOrDie[IOException]
    } yield true

  override def getLatest(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): IO[IOException, Option[Chunk[Byte]]] =
    for {
      dataNamespace    <- getOrCreateNamespace(dataNamespace(namespace))
      versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
      rawVersions      <- rocksDB.get(versionNamespace, key.toArray).refineToOrDie[IOException]
      lastTimestamp    <- getLastTimestamp(rawVersions, before)
      result <- lastTimestamp match {
                  case Some(lastTimestamp) =>
                    rocksDB
                      .get(dataNamespace, getVersionedKey(key, lastTimestamp).toArray)
                      .map(_.map(Chunk.fromArray))
                      .refineToOrDie[IOException]
                  case None => ZIO.none
                }

    } yield result

  override def getLatestTimestamp(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Timestamp]] =
    for {
      versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
      rawVersions      <- rocksDB.get(versionNamespace, key.toArray).refineToOrDie[IOException]
      lastTimestamp    <- getLastTimestamp(rawVersions, None)
    } yield lastTimestamp

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
    ZStream.unwrap {
      for {
        versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
        dataNamespace    <- getOrCreateNamespace(dataNamespace(namespace))
        result = rocksDB
                   .newIterator(versionNamespace)
                   .refineToOrDie[IOException]
                   .mapZIO { case (key, value) =>
                     val keyChunk = Chunk.fromArray(key)
                     getLastTimestamp(Some(value), None).flatMap {
                       case None =>
                         ZIO.succeed(Chunk.empty)
                       case Some(timestamp) =>
                         rocksDB
                           .get(dataNamespace, getVersionedKey(keyChunk, timestamp).toArray)
                           .refineToOrDie[IOException]
                           .map {
                             case None       => Chunk.empty
                             case Some(data) => Chunk(keyChunk -> Chunk.fromArray(data))
                           }
                     }
                   }
                   .flattenChunks
      } yield result
    }

  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
    (for {
      dataNamespace    <- getOrCreateNamespace(dataNamespace(namespace))
      versionNamespace <- getOrCreateNamespace(versionNamespace(namespace))
      rawVersions      <- rocksDB.get(versionNamespace, key.toArray).refineToOrDie[IOException]
      _ <- rawVersions match {
             case Some(rawVersions) =>
               for {
                 versions <- decodeRawVersions(rawVersions)
                 _ <- ZIO.foreachDiscard(versions) { timestamp =>
                        rocksDB.delete(dataNamespace, getVersionedKey(key, timestamp).toArray)
                      }
                 _ <- rocksDB.delete(versionNamespace, key.toArray)
               } yield ()
             case None =>
               ZIO.unit
           }
    } yield ()).refineToOrDie[IOException]

  private def getVersionedKey(key: Chunk[Byte], timestamp: Timestamp): Chunk[Byte] =
    key ++ ("_" + timestamp.value.toString).getBytes(StandardCharsets.UTF_8)

  private def appendTimestamp(rawVersions: Option[Array[Byte]], timestamp: Timestamp): IO[IOException, Chunk[Byte]] =
    rawVersions match {
      case Some(rawVersions) =>
        ProtobufCodec.decode(Schema[List[Timestamp]])(Chunk.fromArray(rawVersions)) match {
          case Left(failure) =>
            ZIO.fail(new IOException(s"Failed to decode versions: $failure"))
          case Right(versions) =>
            val updatedVersions = timestamp :: versions
            ZIO.succeed(ProtobufCodec.encode(Schema[List[Timestamp]])(updatedVersions))
        }
      case None =>
        ZIO.succeed(ProtobufCodec.encode(Schema[List[Timestamp]])(List(timestamp)))
    }

  private def getLastTimestamp(
    rawVersions: Option[Array[Byte]],
    before: Option[Timestamp]
  ): ZIO[Any, IOException, Option[Timestamp]] =
    rawVersions match {
      case Some(rawVersions) =>
        decodeRawVersions(rawVersions)
          .map(_.filter(_ <= before.getOrElse(Timestamp(Long.MaxValue))) match {
            case Nil      => None
            case filtered => Some(filtered.maxBy(_.value))
          })
      case None => ZIO.none
    }

  private def decodeRawVersions(rawVersions: Array[Byte]) =
    ProtobufCodec.decode(Schema[List[Timestamp]])(Chunk.fromArray(rawVersions)) match {
      case Left(failure) =>
        ZIO.fail(new IOException(s"Failed to decode versions: $failure"))
      case Right(versions) =>
        ZIO.succeed(versions)
    }

  private def dataNamespace(namespace: String): String =
    s"data__$namespace"

  private def versionNamespace(namespace: String): String =
    s"version__$namespace"

  private def getOrCreateNamespace(namespace: String): IO[IOException, ColumnFamilyHandle] =
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

object RocksDbKeyValueStore {
  val layer: ZLayer[TransactionDB, IOException, KeyValueStore] =
    ZLayer {
      for {
        rocksDB           <- ZIO.service[TransactionDB]
        initialNamespaces <- getExistingNamespaces(rocksDB)
        namespaces        <- TMap.make[String, Promise[IOException, ColumnFamilyHandle]](initialNamespaces: _*).commit
      } yield {
        RocksDbKeyValueStore(rocksDB, namespaces)
      }
    }

  private def getExistingNamespaces(
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
