package zio.flow.rocksdb.metrics

import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle, ColumnFamilyOptions, FlushOptions, WriteOptions}
import zio.rocksdb.Atomically.{TransactionOnly, TransactionWithSomething}
import zio.rocksdb.iterator.{Direction, Position}
import zio.{Scope, Task, ZIO, stream}
import zio.rocksdb.{Transaction, TransactionDB, WriteBatch}

case class MeteredTransactionDB(store: String, transactionalDb: TransactionDB) extends TransactionDB {
  override def beginTransaction(writeOptions: WriteOptions): ZIO[Scope, Throwable, Transaction] =
    metered("beginTransaction")(transactionalDb.beginTransaction(writeOptions))

  override def atomically[R, E >: Throwable, A](writeOptions: WriteOptions)(zio: ZIO[Transaction with R, E, A])(implicit
    A: TransactionWithSomething
  ): ZIO[R, E, A] =
    metered("atomically")(transactionalDb.atomically[R, E, A](writeOptions)(zio))

  override def atomically[E >: Throwable, A](writeOptions: WriteOptions)(zio: ZIO[Transaction, E, A])(implicit
    A: TransactionOnly
  ): ZIO[Any, E, A] =
    metered("atomically")(transactionalDb.atomically[E, A](writeOptions)(zio))

  override def delete(key: Array[Byte]): Task[Unit] =
    metered("delete")(transactionalDb.delete(key))

  override def delete(cfHandle: ColumnFamilyHandle, key: Array[Byte]): Task[Unit] =
    metered("delete")(transactionalDb.delete(cfHandle, key))

  override def flush(flushOptions: FlushOptions): Task[Unit] =
    metered("flush")(transactionalDb.flush(flushOptions))

  override def flush(flushOptions: FlushOptions, columnFamilyHandle: ColumnFamilyHandle): Task[Unit] =
    metered("flush")(transactionalDb.flush(flushOptions, columnFamilyHandle))

  override def flush(flushOptions: FlushOptions, columnFamilyHandles: List[ColumnFamilyHandle]): Task[Unit] =
    metered("flush")(transactionalDb.flush(flushOptions, columnFamilyHandles))

  override def flushWal(sync: Boolean): Task[Unit] =
    metered("flushWal")(transactionalDb.flushWal(sync))

  override def get(key: Array[Byte]): Task[Option[Array[Byte]]] =
    metered("get")(transactionalDb.get(key))

  override def get(cfHandle: ColumnFamilyHandle, key: Array[Byte]): Task[Option[Array[Byte]]] =
    metered("get")(transactionalDb.get(cfHandle, key))

  override def initialHandles: Task[List[ColumnFamilyHandle]] =
    metered("initialHandles")(transactionalDb.initialHandles)

  override def multiGetAsList(keys: List[Array[Byte]]): Task[List[Option[Array[Byte]]]] =
    metered("multiGetAsList")(transactionalDb.multiGetAsList(keys))

  override def multiGetAsList(
    handles: List[ColumnFamilyHandle],
    keys: List[Array[Byte]]
  ): Task[List[Option[Array[Byte]]]] =
    metered("multiGetAsList")(transactionalDb.multiGetAsList(handles, keys))

  override def newIterator: stream.Stream[Throwable, (Array[Byte], Array[Byte])] =
    transactionalDb.newIterator

  override def newIterator(
    direction: Direction,
    position: Position
  ): stream.Stream[Throwable, (Array[Byte], Array[Byte])] =
    transactionalDb.newIterator(direction, position)

  override def newIterator(cfHandle: ColumnFamilyHandle): stream.Stream[Throwable, (Array[Byte], Array[Byte])] =
    transactionalDb.newIterator(cfHandle)

  override def newIterators(
    cfHandles: List[ColumnFamilyHandle]
  ): stream.Stream[Throwable, (ColumnFamilyHandle, stream.Stream[Throwable, (Array[Byte], Array[Byte])])] =
    transactionalDb.newIterators(cfHandles)

  override def put(key: Array[Byte], value: Array[Byte]): Task[Unit] =
    metered("put")(transactionalDb.put(key, value))

  override def put(cfHandle: ColumnFamilyHandle, key: Array[Byte], value: Array[Byte]): Task[Unit] =
    metered("put")(transactionalDb.put(cfHandle, key, value))

  override def createColumnFamily(columnFamilyDescriptor: ColumnFamilyDescriptor): Task[ColumnFamilyHandle] =
    metered("createColumnFamily")(transactionalDb.createColumnFamily(columnFamilyDescriptor))

  override def createColumnFamilies(
    columnFamilyDescriptors: List[ColumnFamilyDescriptor]
  ): Task[List[ColumnFamilyHandle]] =
    metered("createColumnFamilies")(transactionalDb.createColumnFamilies(columnFamilyDescriptors))

  override def createColumnFamilies(
    columnFamilyOptions: ColumnFamilyOptions,
    columnFamilyNames: List[Array[Byte]]
  ): Task[List[ColumnFamilyHandle]] =
    metered("createColumnFamilies")(transactionalDb.createColumnFamilies(columnFamilyOptions, columnFamilyNames))

  override def dropColumnFamily(columnFamilyHandle: ColumnFamilyHandle): Task[Unit] =
    metered("dropColumnFamily")(transactionalDb.dropColumnFamily(columnFamilyHandle))

  override def dropColumnFamilies(columnFamilyHandles: List[ColumnFamilyHandle]): Task[Unit] =
    metered("dropColumnFamilies")(transactionalDb.dropColumnFamilies(columnFamilyHandles))

  override def write(writeOptions: WriteOptions, writeBatch: WriteBatch): Task[Unit] =
    metered("write")(transactionalDb.write(writeOptions, writeBatch))

  override def newIterator(
    cfHandle: ColumnFamilyHandle,
    direction: Direction,
    position: Position
  ): stream.Stream[Throwable, (Array[Byte], Array[Byte])] =
    transactionalDb.newIterator(cfHandle, direction, position)

  private def metered[R, E, A](operationName: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    zio @@ (rocksdbSuccess(store, operationName) >>> rocksdbFailure(store, operationName) >>> rocksdbLatency(
      store,
      operationName
    ))
}
