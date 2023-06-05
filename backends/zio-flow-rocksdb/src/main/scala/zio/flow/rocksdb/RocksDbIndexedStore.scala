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

import org.rocksdb.{ColumnFamilyHandle, ComparatorOptions}
import org.rocksdb.util.BytewiseComparator
import zio.constraintless.TypeList._
import zio.flow.rocksdb.RocksDbIndexedStore.{codecs, positionKey, bytesComparator}
import zio.flow.rocksdb.metrics.MeteredTransactionDB
import zio.flow.runtime.IndexedStore
import zio.flow.runtime.IndexedStore.Index
import zio.rocksdb.iterator.{Direction, Position}
import zio.rocksdb.{Transaction, TransactionDB}
import zio.schema.codec.BinaryCodecs
import zio.schema.codec.ProtobufCodec._
import zio.stm.TMap
import zio.stream.ZStream
import zio.{Chunk, IO, Promise, Scope, ZIO, ZLayer}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

final case class RocksDbIndexedStore(
  rocksDB: TransactionDB,
  namespaces: TMap[String, Promise[Throwable, ColumnFamilyHandle]]
) extends IndexedStore
    with ColumnFamilyManagement {

  def addTopic(topic: String): IO[Throwable, ColumnFamilyHandle] =
    for {
      // TODO : Only catch "Column Family already exists"
      colFamHandle <- getOrCreateNamespace(topic)
      _ <- rocksDB
             .put(
               colFamHandle,
               positionKey,
               codecs.encode(0L).toArray
             )

    } yield colFamHandle

  override def position(topic: String): IO[Throwable, Index] =
    (for {
      cfHandle <- getOrCreateNamespace(topic)
      positionBytes <-
        rocksDB.get(cfHandle, positionKey).orDie
      position <-
        positionBytes match {
          case Some(positionBytes) =>
            ZIO
              .fromEither(codecs.decode[Long](Chunk.fromArray(positionBytes)))
              .mapError(s => new Throwable(s))
          case None => ZIO.succeed(0L)
        }

    } yield Index(position))

  override def put(topic: String, value: Chunk[Byte]): IO[Throwable, Index] =
    for {
      colFam <- getOrCreateNamespace(topic)
      _ <- rocksDB.atomically {
             for {
               positionBytes <- Transaction
                                  .getForUpdate(
                                    colFam,
                                    positionKey,
                                    exclusive = true
                                  )
               positionBytes1 = incPosition(positionBytes)
               _ <- Transaction
                      .put(
                        colFam,
                        positionKey,
                        positionBytes1
                      )
               _ <-
                 Transaction
                   .put(
                     colFam,
                     positionBytes1,
                     value.toArray
                   )

             } yield ()
           }
      newPosition <- position(topic)
    } yield newPosition

  override def scan(topic: String, position: Index, until: Index): ZStream[Any, Throwable, Chunk[Byte]] =
    ZStream
      .fromZIO(getOrCreateNamespace(topic))
      .flatMap { cf =>
        val untilEncoded = codecs.encode(until: Long).toArray
        for {
          value <-
            rocksDB.newIterator(cf, Direction.Forward, Position.Target(codecs.encode(position: Long))).collectWhile {
              case (key, value) if bytesComparator.compare(ByteBuffer.wrap(key), ByteBuffer.wrap(untilEncoded)) <= 0 =>
                Chunk.fromArray(value)

            }
        } yield value
      }

  override def delete(topic: String): IO[Throwable, Unit] =
    for {
      colFam   <- getOrCreateNamespace(topic)
      position <- position(topic)
      _        <- rocksDB.delete(colFam, positionKey)
      _ <- ZIO.foreachDiscard(0L to position.toLong) { idx =>
             rocksDB.delete(colFam, codecs.encode(idx).toArray)
           }
    } yield ()

  private def incPosition(posBytes: Option[Array[Byte]]): Array[Byte] =
    posBytes match {
      case Some(posBytes) =>
        codecs
          .encode(
            codecs.decode[Long](Chunk.fromArray(posBytes)) match {
              case Left(error) => throw new Throwable(error)
              case Right(p)    => p + 1
            }
          )
          .toArray
      case None =>
        codecs.encode(1L).toArray
    }
}

object RocksDbIndexedStore {

  def make: ZIO[Scope, Throwable, RocksDbIndexedStore] =
    for {
      options <- ZIO.config(RocksDbConfig.config.nested("rocksdb-indexed-store"))
      rocksDb <- TransactionDB.Live.openAllColumnFamilies(
                   options.toDBOptions,
                   options.toColumnFamilyOptions,
                   options.toTransactionDBOptions,
                   options.toJRocksDbPath
                 )
      meteredRocksDb     = MeteredTransactionDB("indexed-store", rocksDb)
      initialNamespaces <- meteredRocksDb.initialHandles
      initialPromiseMap <- ZIO.foreach(initialNamespaces) { handle =>
                             val name = new String(handle.getName, StandardCharsets.UTF_8)
                             Promise.make[Throwable, ColumnFamilyHandle].flatMap { promise =>
                               promise.succeed(handle).as(name -> promise)
                             }
                           }
      namespaces <- TMap.make[String, Promise[Throwable, ColumnFamilyHandle]](initialPromiseMap: _*).commit
    } yield RocksDbIndexedStore(meteredRocksDb, namespaces)

  def layer: ZLayer[Any, Throwable, IndexedStore] = ZLayer.scoped(make)

  def withEmptyTopic(topicName: String): ZLayer[Any, Throwable, IndexedStore] =
    ZLayer.scoped(make.tap(store => store.addTopic(topicName)))

  private lazy val codecs      = BinaryCodecs.make[String :: Long :: End]
  private lazy val positionKey = codecs.encode("POSITION").toArray

  private lazy val bytesComparator = new BytewiseComparator(new ComparatorOptions)

}
