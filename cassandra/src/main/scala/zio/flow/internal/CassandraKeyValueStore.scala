package zio.flow.internal

import com.datastax.oss.driver.api.core.cql.{Row, SimpleStatement}
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal
import com.datastax.oss.driver.api.querybuilder.QueryBuilder

import java.io.IOException
import java.nio.ByteBuffer
import zio.{Chunk, Has, IO, Task, URLayer, ZIO}
import zio.stream.ZStream

final class CassandraKeyValueStore(session: CqlSession) extends KeyValueStore {
  import CassandraKeyValueStore._

  private val keyspace =
    session.getKeyspace
      .orElse(null: CqlIdentifier) // scalafix:ok DisableSyntax.null

  private val table =
    CqlIdentifier.fromCql(CassandraKeyValueStore.tableName)

  private val cqlSelect =
    QueryBuilder.selectFrom(keyspace, table)

  private val cqlInsert =
    QueryBuilder.insertInto(keyspace, table)

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte]
  ): IO[IOException, Boolean] = {
    val insert = cqlInsert
      .value(
        namespaceColumnName,
        literal(namespace)
      )
      .value(
        keyColumnName,
        byteBufferFrom(key)
      )
      .value(
        valueColumnName,
        byteBufferFrom(value)
      )
      .build

    executeAsync(insert, session)
      .mapBoth(
        refineToIOException(s"Error putting key-value pair for <$namespace> namespace"),
        _ => true
      )
  }

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

    executeAsync(query, session).flatMap { result =>
      if (result.remaining > 0)
        Task {
          blobValueOf(valueColumnName, result.one)
        }.asSome
      else
        ZIO.none
    }.mapError(
      refineToIOException(s"Error retrieving or reading value for <$namespace> namespace")
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
      .paginateM(
        executeAsync(query, session)
      )(_.map { result =>
        val pairs =
          ZStream
            .fromJavaIterator(
              result.currentPage.iterator
            )
            .mapM { row =>
              Task {
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

  private def executeAsync(statement: SimpleStatement, session: CqlSession) =
    ZIO.fromCompletionStage(
      session.executeAsync(statement)
    )

  private def byteBufferFrom(bytes: Chunk[Byte]) =
    literal(
      ByteBuffer.wrap(bytes.toArray)
    )

  private def blobValueOf(columnName: String, row: Row) =
    Chunk.fromArray(
      row
        .getByteBuffer(columnName)
        .array
    )

  private def refineToIOException(errorContext: String): Throwable => IOException = {
    case error: IOException =>
      error
    case error =>
      new IOException(s"$errorContext: <${error.getMessage}>.", error)
  }
}

object CassandraKeyValueStore {

  val live: URLayer[Has[CqlSession], Has[KeyValueStore]] =
    ZIO
      .service[CqlSession]
      .map(new CassandraKeyValueStore(_))
      .toLayer

  val tableName: String =
    withDoubleQuotes("_zflow_key_value_store")

  val namespaceColumnName: String =
    withDoubleQuotes("namespace")

  val keyColumnName: String =
    withDoubleQuotes("key")

  val valueColumnName: String =
    withDoubleQuotes("value")

  private def withDoubleQuotes(string: String) =
    "\"" + string + "\""
}
