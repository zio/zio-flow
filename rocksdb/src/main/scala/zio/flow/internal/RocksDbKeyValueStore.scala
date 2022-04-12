package zio.flow.internal

import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle}
import zio.rocksdb.{RocksDB, Transaction, TransactionDB}
import zio.stm.{TMap, ZSTM}
import zio.stream.ZStream
import zio.{Chunk, IO, Promise, ZIO, ZLayer}

import java.io.IOException
import java.nio.charset.StandardCharsets

final case class RocksDbKeyValueStore(
  rocksDB: TransactionDB,
  namespaces: TMap[String, Promise[IOException, ColumnFamilyHandle]]
) extends KeyValueStore {

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
    getOrCreateNamespace(namespace).flatMap { handle =>
      rocksDB.put(handle, key.toArray, value.toArray).refineToOrDie[IOException].as(true)
    }

  def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] =
    for {
      handle <- getOrCreateNamespace(namespace)
      value  <- rocksDB.get(handle, key.toArray).refineToOrDie[IOException]
    } yield value.map(Chunk.fromArray)

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
    ZStream.unwrap {
      getOrCreateNamespace(namespace).map { handle =>
        rocksDB
          .newIterator(handle)
          .map { case (key, value) => Chunk.fromArray(key) -> Chunk.fromArray(value) }
          .refineToOrDie[IOException]
      }
    }

  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
    getOrCreateNamespace(namespace).flatMap { handle =>
      rocksDB.delete(handle, key.toArray).refineToOrDie[IOException]
    }

  override def putAll(items: Chunk[KeyValueStore.Item]): IO[IOException, Unit] =
    rocksDB.atomically {
      ZIO.foreach(items) { item =>
        getOrCreateNamespace(item.namespace).flatMap { handle =>
          Transaction.put(handle, item.key.toArray, item.value.toArray)
        }
      }
    }.refineToOrDie[IOException].unit

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
