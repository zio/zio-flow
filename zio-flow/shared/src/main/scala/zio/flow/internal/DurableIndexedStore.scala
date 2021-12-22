package zio.flow.internal
import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle, Options}
import zio.rocksdb.{Transaction, TransactionDB, service}
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

  //TODO: Initial handles does not get all column handles
  private def getNamespaces(
    transactionDB: service.TransactionDB
  ): IO[IOException, Map[Chunk[Byte], ColumnFamilyHandle]] =
    transactionDB.initialHandles
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
           //TODO : Deal with Chunk.fromArray on Option
           //TODO : Add support for ColumnFamilyHandle
           //TODO : Deal with Protobuf decode and either return type
           Transaction.getForUpdate(
             colFam,
             ProtobufCodec.encode(Schema[String])("POSITION").toArray,
             exclusive = true
           ) >>= { posBytes =>
             Transaction
               .put(
                 colFam,
                 ProtobufCodec.encode(Schema[String])("POSITION").toArray,
                 ProtobufCodec
                   .encode(Schema[Long])(
                     (ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(posBytes.get)).right.get) + 1
                   )
                   .toArray
               ) >>= { _ =>
               Transaction
                 .put(
                   colFam,
                   ProtobufCodec
                     .encode(Schema[Long])(
                       (ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(posBytes.get)).right.get) + 1
                     )
                     .toArray,
                   value.toArray
                 )
                 .refineToOrDie[IOException]
             }
           }
         }.refineToOrDie[IOException]

    newPos <- position(topic)
  } yield newPos

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
  def live(topic: String): ZLayer[Has[service.TransactionDB], Throwable, Has[DurableIndexedStore]] = {
    val database: ZLayer[Any, Throwable, TransactionDB] =
      TransactionDB.live(new Options().setCreateIfMissing(true), "/zio_flow_transaction_db")
    (for {
      transactionDB <- ZIO.service[service.TransactionDB]
      di <-
        ZIO
          .service[service.TransactionDB]
          .map(transactionDB => DurableIndexedStore(transactionDB))
          .provideLayer(database)
      cfHandle <- di.getColFamilyHandle(topic)
      _ <- TransactionDB
             .put(
               cfHandle,
               ProtobufCodec.encode(Schema[String])("POSITION").toArray,
               ProtobufCodec.encode(Schema[Long])(0L).toArray
             )
             .provideLayer(database)
    } yield di).toLayer
  }
}
