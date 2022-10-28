package zio.flow.rocksdb

import java.nio.file.Path
import org.{rocksdb => jrocksdb}

case class RocksDbConfig(path: Path) {
  def toDBOptions: jrocksdb.DBOptions =
    new jrocksdb.DBOptions()
      .setCreateIfMissing(true)

  def toTransactionDBOptions: jrocksdb.TransactionDBOptions =
    new jrocksdb.TransactionDBOptions()

  def toColumnFamilyOptions: jrocksdb.ColumnFamilyOptions =
    new jrocksdb.ColumnFamilyOptions()

  def toJRocksDbPath: String = path.toString
}
