package zio.flow.internal
import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle}
import zio.rocksdb.service.TransactionDB
import zio.rocksdb.{Transaction, service}
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.stream.ZStream
import zio.{Chunk, Has, IO, Task, UIO, ZIO, ZLayer}

import java.io.IOException

final case class DurableIndexedStore(transactionDB: service.TransactionDB) extends IndexedStore {

  def addTopic(topic: String): Task[ColumnFamilyHandle] =
    for {
      colFam <-
        transactionDB.createColumnFamily(
          new ColumnFamilyDescriptor(ProtobufCodec.encode(Schema[String])(topic).toArray)
        )
      _ <- transactionDB.put(
             colFam,
             ProtobufCodec.encode(Schema[String])("POSITION").toArray,
             ProtobufCodec.encode(Schema[Long])(0L).toArray
           )
    } yield colFam

  override def position(topic: String): IO[IOException, Long] = (for {
    cfHandle <- getColFamilyHandle(topic)
    positionBytes <-
      transactionDB.get(cfHandle, ProtobufCodec.encode(Schema[String])("POSITION").toArray).orDie
    position <- ZIO.fromEither(ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(positionBytes.get)))
  } yield position).mapError(s => new IOException(s))

  
  private def getNamespaces(
    transactionDB: service.TransactionDB
  ): IO[IOException, Map[Chunk[Byte], ColumnFamilyHandle]] =
    transactionDB.ownedColumnFamilyHandles()
      .map(_.map(namespace => Chunk.fromArray(namespace.getName) -> namespace).toMap)
      .refineToOrDie[IOException]

  def getColFamilyHandle(topic: String): UIO[ColumnFamilyHandle] =
    for {
      namespaces <- getNamespaces(transactionDB).orDie
      cf         <- ZIO.succeed(namespaces.get(ProtobufCodec.encode(Schema[String])(topic)))
      handle <- cf match {
                  case Some(h) => ZIO.succeed(h)
                  case None    => addTopic(topic).orDie
                }
    } yield handle

  override def put(topic: String, value: Chunk[Byte]): IO[IOException, Long] = for {
    colFam <- getColFamilyHandle(topic)
    _ <- transactionDB.atomically {
           Transaction.getForUpdate(
             colFam,
             ProtobufCodec.encode(Schema[String])("POSITION").toArray,
             exclusive = true
           ) >>= { posBytes =>
             Transaction
               .put(
                 colFam,
                 ProtobufCodec.encode(Schema[String])("POSITION").toArray,
                 incPosition(posBytes)
               ) >>= { _ =>
               Transaction
                 .put(
                   colFam,
                   incPosition(posBytes),
                   value.toArray
                 )
                 //.refineToOrDie[IOException]
             }
           }
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

  override def scan(topic: String, from: Long, inclusiveTo: Long): ZStream[Any, IOException, Chunk[Byte]] =
    ZStream.fromEffect(getColFamilyHandle(topic)).flatMap { cf =>
      for {
        k <- ZStream.fromIterable(from to inclusiveTo)
        value <-
          ZStream
            .fromEffect(transactionDB.get(cf, ProtobufCodec.encode(Schema[Long])(k).toArray).refineToOrDie[IOException])
      } yield value.map(Chunk.fromArray).getOrElse(Chunk.empty)
    }
}

object DurableIndexedStore {

  def live(topic : String): ZLayer[Has[TransactionDB], Throwable, Has[DurableIndexedStore]] = (for {
  transactionDB <- ZIO.service[service.TransactionDB]
  di <- ZIO.succeed(DurableIndexedStore(transactionDB))
  _ <- di.addTopic(topic)
  } yield di).toLayer

  def live: ZLayer[Has[TransactionDB], Throwable, Has[DurableIndexedStore]] = (for {
    transactionDB <- ZIO.service[service.TransactionDB]
    di <- ZIO.succeed(DurableIndexedStore(transactionDB))
  } yield di).toLayer
}
