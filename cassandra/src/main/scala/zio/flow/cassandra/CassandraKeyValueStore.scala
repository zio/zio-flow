package zio.flow.cassandra

import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal
import com.datastax.oss.driver.api.querybuilder.delete.DeleteSelection
import com.datastax.oss.driver.api.querybuilder.insert.InsertInto
import com.datastax.oss.driver.api.querybuilder.select.SelectFrom
import com.datastax.oss.driver.api.querybuilder.update.UpdateStart
import com.datastax.oss.driver.api.querybuilder.{Literal, QueryBuilder}
import zio.flow.RemoteVariableVersion
import zio.flow.cassandra.CassandraKeyValueStore._
import zio.flow.internal.KeyValueStore
import zio.flow.internal.KeyValueStore.Item
import zio.stream.ZStream
import zio.{Chunk, IO, Task, URLayer, ZIO}

import java.io.IOException
import java.nio.ByteBuffer

final class CassandraKeyValueStore(session: CqlSession) extends KeyValueStore {

  private val keyspace =
    session.getKeyspace
      .orElse(null: CqlIdentifier) // scalafix:ok DisableSyntax.null

  private val cqlSelect: SelectFrom =
    QueryBuilder.selectFrom(keyspace, table)

  private val cqlSelectVersion: SelectFrom =
    QueryBuilder.selectFrom(keyspace, versionTable)

  private val cqlInsert: InsertInto =
    QueryBuilder.insertInto(keyspace, table)

  private val cqlUpdate: UpdateStart =
    QueryBuilder.update(keyspace, versionTable)

  private val cqlDelete: DeleteSelection =
    QueryBuilder.deleteFrom(keyspace, table)

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte]
  ): IO[IOException, Boolean] =
    putAll(Chunk(Item(namespace, key, value))).as(true)

  override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] = {
    val query = cqlSelect
      .column(valueColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .whereColumn(keyColumnName)
      .isEqualTo(
        byteBufferFrom(key)
      )
      .limit(1)
      .build

    executeAsync(query).flatMap { result =>
      if (result.remaining > 0)
        Task.attempt {
          Option(blobValueOf(valueColumnName, result.one))
        }
      else
        ZIO.none
    }.mapError(
      refineToIOException(s"Error retrieving or reading value for <$namespace> namespace")
    )
  }

  override def getVersion(namespace: String, key: Chunk[Byte]): IO[IOException, Option[RemoteVariableVersion]] = {
    val query = cqlSelectVersion
      .column(versionColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .whereColumn(keyColumnName)
      .isEqualTo(
        byteBufferFrom(key)
      )
      .limit(1)
      .build

    executeAsync(query).flatMap { result =>
      if (result.remaining > 0)
        Task.attempt {
          Option(longValueOf(versionColumnName, result.one)).map(RemoteVariableVersion(_))
        }
      else
        ZIO.none
    }.mapError(
      refineToIOException(s"Error retrieving or reading version for <$namespace> namespace")
    )
  }

  override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] = {
    val query = cqlSelect
      .column(keyColumnName)
      .column(valueColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .build

    lazy val errorContext =
      s"Error scanning all key-value pairs for <$namespace> namespace"

    ZStream
      .paginateZIO(
        executeAsync(query)
      )(_.map { result =>
        val pairs =
          ZStream
            .fromJavaIterator(
              result.currentPage.iterator
            )
            .mapZIO { row =>
              Task.attempt {
                blobValueOf(keyColumnName, row) -> blobValueOf(valueColumnName, row)
              }
            }
            .mapError(
              refineToIOException(errorContext)
            )

        val nextPage =
          if (result.hasMorePages)
            Option(
              ZIO.fromCompletionStage(result.fetchNextPage())
            )
          else
            None

        (pairs, nextPage)
      })
      .mapError(
        refineToIOException(errorContext)
      )
      .flatten
  }

  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] = {
    val delete = cqlDelete
      .column(valueColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .whereColumn(keyColumnName)
      .isEqualTo(
        byteBufferFrom(key)
      )
      .build

    executeAsync(delete)
      .mapBoth(
        refineToIOException(s"Error deleting key-value pair from <$namespace> namespace"),
        _ => ()
      )
  }

  override def putAll(items: Chunk[KeyValueStore.Item]): IO[IOException, Unit] = {
    val dataBatch             = new BatchStatementBuilder(BatchType.LOGGED)
    val versionIncrementBatch = new BatchStatementBuilder(BatchType.COUNTER)
    dataBatch.addStatements(
      items.map(item => toInsert(item)): _*
    )
    versionIncrementBatch.addStatements(
      items.map(item => toVersionIncrement(item)): _*
    )

    // NOTE: worst-case scenario is that version increment succeeds but data update does not, but that should only
    //       cause unnecessary retries, not inconsistent states
    executeAsync(versionIncrementBatch.build())
      .mapBoth(
        refineToIOException(s"Error putting multiple key-value pairs"),
        _ => ()
      ) *>
      executeAsync(dataBatch.build())
        .mapBoth(
          refineToIOException(s"Error putting multiple key-value pairs"),
          _ => ()
        )
  }

  private def toInsert(item: KeyValueStore.Item): SimpleStatement =
    cqlInsert
      .value(
        namespaceColumnName,
        literal(item.namespace)
      )
      .value(
        keyColumnName,
        byteBufferFrom(item.key)
      )
      .value(
        valueColumnName,
        byteBufferFrom(item.value)
      )
      .build

  private def toVersionIncrement(item: KeyValueStore.Item): SimpleStatement =
    cqlUpdate
      .increment(
        versionColumnName
      )
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(item.namespace)
      )
      .whereColumn(keyColumnName)
      .isEqualTo(
        byteBufferFrom(item.key)
      )
      .build

  private def executeAsync(statement: Statement[_]): Task[AsyncResultSet] =
    ZIO.fromCompletionStage(
      session.executeAsync(statement)
    )
}

object CassandraKeyValueStore {

  val layer: URLayer[CqlSession, KeyValueStore] =
    ZIO
      .service[CqlSession]
      .map(new CassandraKeyValueStore(_))
      .toLayer

  private[cassandra] val tableName: String =
    withDoubleQuotes("_zflow_key_value_store")

  private[cassandra] val versionTableName: String =
    withDoubleQuotes("_zflow_key_value_store_versions")

  private[cassandra] val namespaceColumnName: String =
    withColumnPrefix("namespace")

  private[cassandra] val keyColumnName: String =
    withColumnPrefix("key")

  private[cassandra] val valueColumnName: String =
    withColumnPrefix("value")

  private[cassandra] val versionColumnName: String =
    withColumnPrefix("version")

  private val table: CqlIdentifier =
    CqlIdentifier.fromCql(tableName)

  private val versionTable: CqlIdentifier =
    CqlIdentifier.fromCql(versionTableName)

  private def byteBufferFrom(bytes: Chunk[Byte]): Literal =
    literal(
      ByteBuffer.wrap(bytes.toArray)
    )

  private def blobValueOf(columnName: String, row: Row): Chunk[Byte] =
    Chunk.fromArray(
      row
        .getByteBuffer(columnName)
        .array
    )

  private def longValueOf(columnName: String, row: Row): Long =
    row.getLong(columnName)

  private def refineToIOException(errorContext: String): Throwable => IOException = {
    case error: IOException =>
      error
    case error =>
      new IOException(s"$errorContext: <${error.getMessage}>.", error)
  }

  private def withColumnPrefix(s: String) =
    withDoubleQuotes("zflow_kv_" + s)

  private def withDoubleQuotes(string: String) =
    "\"" + string + "\""
}
