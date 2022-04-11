package zio.flow.internal

import org.rocksdb.ColumnFamilyHandle
import zio.rocksdb.{RocksDB, Transaction, TransactionDB}
import zio.stream.ZStream
import zio.{Chunk, IO, ZIO, ZLayer}

import java.io.IOException

final case class RocksDbKeyValueStore(
  rocksDB: TransactionDB,
  namespaces: Map[Chunk[Byte], ColumnFamilyHandle]
) extends KeyValueStore {

  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
    rocksDB.put(getNamespace(namespace), key.toArray, value.toArray).refineToOrDie[IOException].as(true)

  def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] =
    for {
      value <- rocksDB.get(getNamespace(namespace), key.toArray).refineToOrDie[IOException]
    } yield value.map(Chunk.fromArray)

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
    rocksDB
      .newIterator(getNamespace(namespace))
      .map { case (key, value) => Chunk.fromArray(key) -> Chunk.fromArray(value) }
      .refineToOrDie[IOException]

  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] =
    rocksDB.delete(getNamespace(namespace), key.toArray).refineToOrDie[IOException]

  override def putAll(items: Chunk[KeyValueStore.Item]): IO[IOException, Unit] =
    rocksDB.atomically {
      ZIO.foreach(items) { item =>
        Transaction.put(getNamespace(item.namespace), item.key.toArray, item.value.toArray)
      }
    }.refineToOrDie[IOException].unit

  private def getNamespace(namespace: String): ColumnFamilyHandle =
    namespaces(Chunk.fromArray(namespace.getBytes))
}

object RocksDbKeyValueStore {
  val layer: ZLayer[TransactionDB, IOException, KeyValueStore] =
    ZLayer {
      for {
        rocksDB    <- ZIO.service[TransactionDB]
        namespaces <- getNamespaces(rocksDB)
      } yield {
        RocksDbKeyValueStore(rocksDB, namespaces)
      }
    }

  private def getNamespaces(rocksDB: RocksDB): IO[IOException, Map[Chunk[Byte], ColumnFamilyHandle]] =
    rocksDB.initialHandles
      .map(_.map { namespace =>
        Chunk.fromArray(namespace.getName) -> namespace
      }.toMap)
      .refineToOrDie[IOException]

}
