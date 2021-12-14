package zio.flow.server

import java.io.IOException

import org.rocksdb.ColumnFamilyHandle
import zio._
import zio.rocksdb._
import zio.stream._

trait KeyValueStore {
  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean]
  def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]]
  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])]
}

object KeyValueStore {

  val live: ZLayer[RocksDB, IOException, Has[KeyValueStore]] =
    ZLayer.fromEffect {
      for {
        rocksDB    <- ZIO.service[service.RocksDB]
        namespaces <- getNamespaces(rocksDB)
      } yield KeyValueStoreLive(rocksDB, namespaces)
    }

  private final case class KeyValueStoreLive(rocksDB: service.RocksDB, namespaces: Map[Chunk[Byte], ColumnFamilyHandle])
      extends KeyValueStore {

    def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
      for {
        namespace <- getNamespace(namespace)
        _         <- rocksDB.put(namespace, key.toArray, value.toArray).refineToOrDie[IOException]
      } yield true

    def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] =
      for {
        namespace <- getNamespace(namespace)
        value     <- rocksDB.get(namespace, key.toArray).refineToOrDie[IOException]
      } yield value.map(Chunk.fromArray)

    def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
      ZStream.unwrap {
        getNamespace(namespace).map { namespace =>
          rocksDB
            .newIterator(namespace)
            .map { case (key, value) => Chunk.fromArray(key) -> Chunk.fromArray(value) }
            .refineToOrDie[IOException]
        }
      }

    def getNamespace(namespace: String): UIO[ColumnFamilyHandle] =
      ZIO.succeed(namespaces(Chunk.fromArray(namespace.getBytes)))
  }

  private def getNamespaces(rocksDB: service.RocksDB): IO[IOException, Map[Chunk[Byte], ColumnFamilyHandle]] =
    rocksDB.initialHandles
      .map(_.map(namespace => Chunk.fromArray(namespace.getName) -> namespace).toMap)
      .refineToOrDie[IOException]
}
