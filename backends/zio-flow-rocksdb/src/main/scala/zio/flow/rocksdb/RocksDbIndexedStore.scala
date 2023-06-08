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

import org.rocksdb.util.BytewiseComparator
import org.rocksdb.{ColumnFamilyHandle, ComparatorOptions}
import zio.constraintless.TypeList._
import zio.flow.rocksdb.RocksDbIndexedStore.{bytesComparator, codecs, positionKey}
import zio.flow.rocksdb.metrics.MeteredTransactionDB
import zio.flow.runtime.IndexedStore
import zio.flow.runtime.IndexedStore.Index
import zio.rocksdb.iterator.{Direction, Position}
import zio.rocksdb.{Transaction, TransactionDB}
import zio.schema.codec.{BinaryCodec, BinaryCodecs, DecodeError}
import zio.stm.TMap
import zio.stream.{ZPipeline, ZStream}
import zio.{Cause, Chunk, IO, Promise, Scope, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.nio.{BufferUnderflowException, ByteBuffer}

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
            println(Chunk.fromArray(positionBytes))
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
              case (key, value)
                  // Via byte ordering test if the key is before the end
                  if bytesComparator.compare(ByteBuffer.wrap(key), ByteBuffer.wrap(untilEncoded)) <= 0 =>
                if (
                  bytesComparator
                    .compare(ByteBuffer.wrap(key), ByteBuffer.wrap(positionKey)) != 0
                )
                  List(Chunk.fromArray(value))
                else Nil

            }
        } yield value
      }
      .flattenIterables

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

  implicit val longCodec: BinaryCodec[Long] =
    new BinaryCodec[Long] {
      override def decode(whole: Chunk[Byte]): Either[DecodeError, Long] =
        try Right(ByteBuffer.wrap(whole.toArray).getLong)
        catch {
          case ex: BufferUnderflowException =>
            Left(DecodeError.ReadError(Cause.fail(ex), "Not enough bytes to decode a long"))
        }

      override def streamDecoder: ZPipeline[Any, DecodeError, Byte, Long] =
        ZPipeline.rechunk(4).mapChunksZIO((chunk: Chunk[Byte]) => ZIO.fromEither(decode(chunk)).map(Chunk.single))

      override def encode(value: Long): Chunk[Byte] = {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(value)
        buffer.flip()
        Chunk.fromByteBuffer(buffer)
      }

      override def streamEncoder: ZPipeline[Any, Nothing, Long, Byte] =
        ZPipeline.map(long => encode(long)).flattenChunks
    }

  implicit val stringCodec: BinaryCodec[String] =
    new BinaryCodec[String] {
      override def decode(whole: Chunk[Byte]): Either[DecodeError, String] = {
        val buffer = ByteBuffer.wrap(whole.toArray)
        val l      = buffer.getInt
        val bytes  = buffer.slice(4, l)
        Right(new String(bytes.array(), StandardCharsets.UTF_8))
      }

      override def streamDecoder: ZPipeline[Any, DecodeError, Byte, String] =
        ??? // TODO (not used now)

      override def encode(value: String): Chunk[Byte] = {
        val bytes  = value.getBytes(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + bytes.length)
        buffer.putInt(bytes.length)
        buffer.put(bytes)
        buffer.flip()
        Chunk.fromByteBuffer(buffer)
      }

      override def streamEncoder: ZPipeline[Any, Nothing, String, Byte] =
        ZPipeline.map(string => encode(string)).flattenChunks
    }
}
