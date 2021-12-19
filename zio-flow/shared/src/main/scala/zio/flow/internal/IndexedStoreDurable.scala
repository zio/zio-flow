package zio.flow.internal
import org.rocksdb.ColumnFamilyHandle
import zio.rocksdb.service
import zio.{Chunk, IO, UIO, ZIO}
import zio.stream.ZStream

import java.io.IOException

object IndexedStoreDurable {

  final case class IndexedStoreLive(rocksDB: service.RocksDB) extends IndexedStore {

    private def incrementPosition(topic: String): IO[IOException, Long] = ???

    private def getNamespaces(rocksDB: service.RocksDB): IO[IOException, Map[Chunk[Byte], ColumnFamilyHandle]] =
      rocksDB.initialHandles
        .map(_.map(namespace => Chunk.fromArray(namespace.getName) -> namespace).toMap)
        .refineToOrDie[IOException]

    def getColFamilyHandle(namespace: String): UIO[ColumnFamilyHandle] =
      for {
        namespaces <- getNamespaces(rocksDB).orDie
        cf         <- ZIO.succeed(namespaces(Chunk.fromArray(namespace.getBytes)))
      } yield cf

    override def position(topic: String): IO[IOException, Long] = for {
      cfHandle <- getColFamilyHandle(topic)
      position <- rocksDB.get(cfHandle, "POSITION".getBytes()).orDie
    } yield new String(position.get).toLong // TODO: use zio-schema, protobuf encoding instead

    override def put(topic: String, value: Chunk[Byte]): IO[IOException, Long] = for {
      namespace <- getColFamilyHandle(topic)
      pos       <- incrementPosition(topic)
      _         <- rocksDB.put(namespace, pos.toString.getBytes(), value.toArray).refineToOrDie[IOException]
    } yield pos

    override def scan(topic: String, from: Long, inclusiveTo: Long): ZStream[Any, IOException, Chunk[Byte]] =
      ZStream.fromEffect(getColFamilyHandle(topic)).flatMap { cf =>
        for {
          k     <- ZStream.fromIterable(from to inclusiveTo)
          value <- ZStream.fromEffect(rocksDB.get(cf, k.toString.getBytes()).refineToOrDie[IOException])
        } yield value.map(Chunk.fromArray).getOrElse(Chunk.empty)
      }
  }
}
