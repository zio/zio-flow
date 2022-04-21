package zio.flow.internal

import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle}
import zio.flow.RemoteVariableVersion
import zio.flow.internal.RocksDbKeyValueStore.{decodeLong, encodeLong}
import zio.rocksdb.{RocksDB, Transaction, TransactionDB}
import zio.stm.{TMap, ZSTM}
import zio.stream.ZStream
import zio.{Chunk, IO, Promise, ZIO, ZLayer}

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

final case class RocksDbKeyValueStore(
  rocksDB: TransactionDB,
  namespaces: TMap[String, Promise[IOException, ColumnFamilyHandle]]
) extends KeyValueStore {

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
    putAll(Chunk(KeyValueStore.Item(namespace, key, value))).as(true)

  def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] =
    for {
      handle <- getOrCreateNamespace(dataNamespace(namespace))
      value  <- rocksDB.get(handle, key.toArray).refineToOrDie[IOException]
    } yield value.map(Chunk.fromArray)

  override def getVersion(namespace: String, key: Chunk[Byte]): IO[IOException, Option[RemoteVariableVersion]] =
    for {
      handle <- getOrCreateNamespace(versionNamespace(namespace))
      value  <- rocksDB.get(handle, key.toArray).refineToOrDie[IOException]
    } yield value.map(Chunk.fromArray).map(decodeLong).map(RemoteVariableVersion(_))

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
    ZStream.unwrap {
      getOrCreateNamespace(dataNamespace(namespace)).map { handle =>
        rocksDB
          .newIterator(handle)
          .map { case (key, value) => Chunk.fromArray(key) -> Chunk.fromArray(value) }
          .refineToOrDie[IOException]
      }
    }

  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
    getOrCreateNamespace(dataNamespace(namespace)).flatMap { handle =>
      rocksDB.delete(handle, key.toArray)
    }.refineToOrDie[IOException] *>
      getOrCreateNamespace(versionNamespace(namespace)).flatMap { handle =>
        rocksDB.delete(handle, key.toArray)
      }.refineToOrDie[IOException]

  override def putAll(items: Chunk[KeyValueStore.Item]): IO[IOException, Unit] =
    rocksDB.atomically {
      ZIO.foreach(items) { item =>
        for {
          dataNamespace    <- getOrCreateNamespace(dataNamespace(item.namespace))
          versionNamespace <- getOrCreateNamespace(versionNamespace(item.namespace))
          _                <- Transaction.put(dataNamespace, item.key.toArray, item.value.toArray)
          lastVersion      <- Transaction.getForUpdate(versionNamespace, item.key.toArray, exclusive = true)
          _ <- Transaction.put(
                 versionNamespace,
                 item.key.toArray,
                 encodeLong(lastVersion.map { lastVersionArray =>
                   decodeLong(Chunk.fromArray(lastVersionArray)) + 1
                 }.getOrElse(0L)).toArray
               )
        } yield ()
      }
    }.refineToOrDie[IOException].unit

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

  private def encodeLong(value: Long): Chunk[Byte] = {
    val buffer = ByteBuffer.allocate(8)
    buffer.putLong(value)
    Chunk.fromArray(buffer.array())
  }

  private def decodeLong(buffer: Chunk[Byte]): Long = {
    val byteBuffer = ByteBuffer.wrap(buffer.toArray)
    byteBuffer.getLong()
  }
}
