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

import org.rocksdb.ColumnFamilyHandle
import zio.flow.runtime.IndexedStore
import zio.flow.runtime.IndexedStore.Index
import zio.rocksdb.{Transaction, TransactionDB}
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.stm.TMap
import zio.stream.ZStream
import zio.{Chunk, IO, Promise, Scope, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

final case class RocksDbIndexedStore(
  rocksDB: TransactionDB,
  namespaces: TMap[String, Promise[Throwable, ColumnFamilyHandle]]
) extends IndexedStore
    with ColumnFamilyManagement {

  def addTopic(topic: String): IO[Throwable, ColumnFamilyHandle] =
    for {
      //TODO : Only catch "Column Family already exists"
      colFamHandle <- getOrCreateNamespace(topic)
      _ <- rocksDB
             .put(
               colFamHandle,
               ProtobufCodec.encode(Schema[String])("POSITION").toArray,
               ProtobufCodec.encode(Schema[Long])(0L).toArray
             )

    } yield colFamHandle

  override def position(topic: String): IO[Throwable, Index] =
    (for {
      cfHandle <- getOrCreateNamespace(topic)
      positionBytes <-
        rocksDB.get(cfHandle, ProtobufCodec.encode(Schema[String])("POSITION").toArray).orDie
      position <-
        positionBytes match {
          case Some(positionBytes) =>
            ZIO
              .fromEither(ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(positionBytes)))
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
                                    ProtobufCodec.encode(Schema[String])("POSITION").toArray,
                                    exclusive = true
                                  )
               positionBytes1 = incPosition(positionBytes)
               _ <- Transaction
                      .put(
                        colFam,
                        ProtobufCodec.encode(Schema[String])("POSITION").toArray,
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

  private def incPosition(posBytes: Option[Array[Byte]]): Array[Byte] =
    posBytes match {
      case Some(posBytes) =>
        ProtobufCodec
          .encode(Schema[Long])(
            ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(posBytes)) match {
              case Left(error) => throw new Throwable(error)
              case Right(p)    => p + 1
            }
          )
          .toArray
      case None =>
        ProtobufCodec.encode(Schema[Long])(1L).toArray
    }

  def scan(topic: String, position: Index, until: Index): ZStream[Any, Throwable, Chunk[Byte]] =
    ZStream.fromZIO(getOrCreateNamespace(topic)).flatMap { cf =>
      for {
        k <- ZStream.fromIterable(position to until)
        value <-
          ZStream
            .fromZIO(rocksDB.get(cf, ProtobufCodec.encode(Schema[Long])(k).toArray))
      } yield value.map(Chunk.fromArray).getOrElse(Chunk.empty)
    }
}

object RocksDbIndexedStore {

  def make: ZIO[RocksDbConfig with Scope, Throwable, RocksDbIndexedStore] =
    for {
      options <- ZIO.service[RocksDbConfig]
      rocksDb <- TransactionDB.Live.openAllColumnFamilies(
                   options.toDBOptions,
                   options.toColumnFamilyOptions,
                   options.toTransactionDBOptions,
                   options.toJRocksDbPath
                 )
      initialNamespaces <- rocksDb.initialHandles
      initialPromiseMap <- ZIO.foreach(initialNamespaces) { handle =>
                             val name = new String(handle.getName, StandardCharsets.UTF_8)
                             Promise.make[Throwable, ColumnFamilyHandle].flatMap { promise =>
                               promise.succeed(handle).as(name -> promise)
                             }
                           }
      namespaces <- TMap.make[String, Promise[Throwable, ColumnFamilyHandle]](initialPromiseMap: _*).commit
    } yield RocksDbIndexedStore(rocksDb, namespaces)

  def layer: ZLayer[RocksDbConfig, Throwable, IndexedStore] = ZLayer.scoped(make)

  def withEmptyTopic(topicName: String): ZLayer[RocksDbConfig, Throwable, IndexedStore] =
    ZLayer.scoped(make.tap(store => store.addTopic(topicName)))
}
