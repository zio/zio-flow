package zio.flow.internal
import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle, RocksDBException}
import zio.rocksdb.service.TransactionDB
import zio.rocksdb.{Transaction, service}
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.stream.ZStream
import zio.{Chunk, Has, IO, Ref, UIO, ZIO, ZLayer}

import java.io.IOException

final case class DurableIndexedStore(transactionDB: service.TransactionDB, colHandles: Ref[List[ColumnFamilyHandle]])
    extends IndexedStore {

  def addTopic(topic: String): IO[IOException, ColumnFamilyHandle] =
    for {
      //TODO : Only catch "Column Family already exists"
      colFamHandle <-
        transactionDB
          .createColumnFamily(new ColumnFamilyDescriptor(ProtobufCodec.encode(Schema[String])(topic).toArray))
          .catchSome { case _: RocksDBException =>
            getColFamilyHandle(topic)
          }
          .refineToOrDie[IOException]
      _ <- transactionDB
             .put(
               colFamHandle,
               ProtobufCodec.encode(Schema[String])("POSITION").toArray,
               ProtobufCodec.encode(Schema[Long])(0L).toArray
             )
             .refineToOrDie[IOException]
      _ <- colHandles.update(list => colFamHandle :: list)
    } yield colFamHandle

  override def position(topic: String): IO[IOException, Long] = (for {
    cfHandle <- getColFamilyHandle(topic)
    positionBytes <-
      transactionDB.get(cfHandle, ProtobufCodec.encode(Schema[String])("POSITION").toArray).orDie
    position <- ZIO.fromEither(ProtobufCodec.decode(Schema[Long])(Chunk.fromArray(positionBytes.get)))
  } yield position).mapError(s => new IOException(s))

  def getNamespaces(): IO[IOException, Map[Chunk[Byte], ColumnFamilyHandle]] =
    for {
      list <- colHandles.get
      map  <- ZIO.succeed(list.map(cHandle => Chunk.fromArray(cHandle.getName) -> cHandle).toMap)
    } yield map

  def getColFamilyHandle(topic: String): UIO[ColumnFamilyHandle] =
    for {
      namespaces <- getNamespaces().orDie
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
                 .refineToOrDie[IOException]
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

  def live(topic: String): ZLayer[Has[TransactionDB], Throwable, Has[DurableIndexedStore]] = (for {
    colHandles    <- Ref.make(List.empty[ColumnFamilyHandle])
    transactionDB <- ZIO.service[service.TransactionDB]
    di            <- ZIO.succeed(DurableIndexedStore(transactionDB, colHandles))
    _             <- di.addTopic(topic)
  } yield di).toLayer

  def live: ZLayer[Has[TransactionDB], Throwable, Has[DurableIndexedStore]] = (for {
    colHandles    <- Ref.make(List.empty[ColumnFamilyHandle])
    transactionDB <- ZIO.service[service.TransactionDB]
    di            <- ZIO.succeed(DurableIndexedStore(transactionDB, colHandles))
  } yield di).toLayer
}
