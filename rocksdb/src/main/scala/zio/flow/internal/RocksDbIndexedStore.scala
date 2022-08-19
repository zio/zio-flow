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

import org.rocksdb.ColumnFamilyHandle
import zio.flow.internal.IndexedStore.Index
import zio.rocksdb.{Transaction, TransactionDB}
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.stm.TMap
import zio.stream.ZStream
import zio.{Chunk, IO, Promise, ZIO, ZLayer}

import java.io.IOException

final case class RocksDbIndexedStore(
  rocksDB: TransactionDB,
  namespaces: TMap[String, Promise[IOException, ColumnFamilyHandle]]
) extends IndexedStore
    with ColumnFamilyManagement {

  def addTopic(topic: String): IO[IOException, ColumnFamilyHandle] =
    for {
      //TODO : Only catch "Column Family already exists"
      colFamHandle <- getOrCreateNamespace(topic)
      _ <- rocksDB
             .put(
               colFamHandle,
               ProtobufCodec.encode(Schema[String])("POSITION").toArray,
               ProtobufCodec.encode(Schema[Long])(0L).toArray
             )
             .refineToOrDie[IOException]
    } yield colFamHandle

  override def position(topic: String): IO[IOException, Index] =
    (for {
      cfHandle <- getOrCreateNamespace(topic)
      positionBytes <-
        rocksDB.get(cfHandle, ProtobufCodec.encode(Schema[String])("POSITION").toArray).orDie
      position <- ZIO
                    .fromEither(ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(positionBytes.get)))
                    .mapError(s => new IOException(s))
    } yield Index(position))

  override def put(topic: String, value: Chunk[Byte]): IO[IOException, Index] =
    for {
      colFam <- getOrCreateNamespace(topic)
      _ <- rocksDB.atomically {
             for {
               posBytes <- Transaction
                             .getForUpdate(
                               colFam,
                               ProtobufCodec.encode(Schema[String])("POSITION").toArray,
                               exclusive = true
                             )
               _ <- Transaction
                      .put(
                        colFam,
                        ProtobufCodec.encode(Schema[String])("POSITION").toArray,
                        incPosition(posBytes)
                      )
               _ <-
                 Transaction
                   .put(
                     colFam,
                     incPosition(posBytes),
                     value.toArray
                   )
                   .refineToOrDie[IOException]
             } yield ()
           }.refineToOrDie[IOException]
      newPos <- position(topic)
    } yield newPos

  private def incPosition(posBytes: Option[Array[Byte]]): Array[Byte] =
    ProtobufCodec
      .encode(Schema[Long])(
        ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(posBytes.get)) match {
          case Left(error) => throw new IOException(error)
          case Right(p)    => p + 1
        }
      )
      .toArray

  def scan(topic: String, position: Index, until: Index): ZStream[Any, IOException, Chunk[Byte]] =
    ZStream.fromZIO(getOrCreateNamespace(topic)).flatMap { cf =>
      for {
        k <- ZStream.fromIterable(position to until)
        value <-
          ZStream
            .fromZIO(rocksDB.get(cf, ProtobufCodec.encode(Schema[Long])(k).toArray).refineToOrDie[IOException])
      } yield value.map(Chunk.fromArray).getOrElse(Chunk.empty)
    }
}

object RocksDbIndexedStore {

  def make: ZIO[TransactionDB, IOException, RocksDbIndexedStore] =
    for {
      rocksDb           <- ZIO.service[TransactionDB]
      initialNamespaces <- ColumnFamilyManagement.getExistingNamespaces(rocksDb)
      namespaces        <- TMap.make[String, Promise[IOException, ColumnFamilyHandle]](initialNamespaces: _*).commit
    } yield RocksDbIndexedStore(rocksDb, namespaces)

  def layer: ZLayer[TransactionDB, Throwable, IndexedStore] = ZLayer(make)

  def withEmptyTopic(topicName: String): ZLayer[TransactionDB, IOException, IndexedStore] =
    ZLayer(make.tap(store => store.addTopic(topicName)))
}
