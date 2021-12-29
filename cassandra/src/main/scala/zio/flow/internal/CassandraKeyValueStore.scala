package zio.flow.internal

import com.datastax.oss.driver.api.core.`type`.DataTypes
import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, Row, SimpleStatement}
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal
import com.datastax.oss.driver.api.querybuilder.{QueryBuilder, SchemaBuilder}

import java.io.IOException
import java.nio.ByteBuffer
import zio.{Chunk, Has, IO, URLayer, ZIO, ZRef}
import zio.stream.ZStream

final class CassandraKeyValueStore(session: CqlSession) extends KeyValueStore {
  import CassandraKeyValueStore._

  private val keyspace =
    session.getKeyspace
      .orElse(null: CqlIdentifier)

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte]
  ): IO[IOException, Boolean] = {
    val insert =
      QueryBuilder
        .insertInto(keyspace, tableOf(namespace))
        .value(
          keyColumnName,
          byteBufferFrom(key)
        )
        .value(
          valueColumnName,
          byteBufferFrom(value)
        )
        .build

    val operation =
      executeAsync(insert, session).as(true)

    operation.catchSome {
      case error: InvalidQueryException if tableDoesNotExist(error, namespace) =>
        val createTable =
          SchemaBuilder
            .createTable(keyspace, tableOf(namespace))
            .withPartitionKey(keyColumnName, DataTypes.BLOB)
            .withColumn(valueColumnName, DataTypes.BLOB)
            .build

        executeAsync(createTable, session) *> operation
    }.mapError(
      refineToIOException("Error putting key-value pair")
    )
  }

  override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] = {
    val query =
      QueryBuilder
        .selectFrom(keyspace, tableOf(namespace))
        .column(valueColumnName)
        .whereColumn(keyColumnName)
        .isEqualTo(
          byteBufferFrom(key)
        )
        .limit(1)
        .build

    val operation =
      executeAsync(query, session).map { result =>
        if (result.remaining > 0)
          Option(
            blobValueOf(valueColumnName, result.one)
          )
        else None
      }.catchSome {
        case error: InvalidQueryException if tableDoesNotExist(error, namespace) =>
          ZIO.none
      }

    operation.mapError(
      refineToIOException("Error retrieving or reading value")
    )
  }

  override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] = {
    val operation =
      ZStream.unwrap {
        val query =
          QueryBuilder
            .selectFrom(keyspace, tableOf(namespace))
            .column(keyColumnName)
            .column(valueColumnName)
            .build

        executeAsync(query, session).map { pageResult1 =>
          val morePages =
            if (pageResult1.hasMorePages)
              pageMoreFrom(pageResult1)
            else
              ZStream.empty

          val retrieved = byteChunkPairsFrom(pageResult1)

          ZStream
            .fromChunk(retrieved)
            .concat(morePages)
        }
      }.catchSome {
        case error: InvalidQueryException if tableDoesNotExist(error, namespace) =>
          ZStream.empty
      }

    operation.mapError(
      refineToIOException("Error scanning all key-value pairs")
    )
  }

  private def executeAsync(statement: SimpleStatement, session: CqlSession) =
    ZIO.fromCompletionStage(
      session.executeAsync(statement)
    )

  private def pageMoreFrom(pageResult1: AsyncResultSet) =
    ZStream {
      for {
        hasMorePagesRef <- ZRef.make(true).toManaged_
        currentPageRef  <- ZRef.make(pageResult1).toManaged_
        paging =
          for {
            hasMorePages <- hasMorePagesRef.get
            currentPage  <- currentPageRef.get
            byteChunkPairs <-
              if (hasMorePages) {
                for {
                  newPageResult <-
                    ZIO
                      .fromCompletionStage(
                        currentPage.fetchNextPage()
                      )
                      .mapError(Option(_))
                  _ <- hasMorePagesRef
                         .set(newPageResult.hasMorePages)
                  _ <- currentPageRef
                         .set(newPageResult)
                } yield {
                  byteChunkPairsFrom(newPageResult)
                }
              } else ZIO.fail(None)
          } yield byteChunkPairs
      } yield paging
    }

  private def tableOf(name: String) =
    CqlIdentifier.fromInternal(name)

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

  private def byteChunkPairsFrom(cResult: AsyncResultSet) = {
    import scala.collection.JavaConverters._

    val keyValuePairs =
      cResult.currentPage.asScala.map { row =>
        blobValueOf(keyColumnName, row) -> blobValueOf(valueColumnName, row)
      }

    Chunk.fromIterable(keyValuePairs)
  }

  private def tableDoesNotExist(error: InvalidQueryException, targetTable: String) = {
    val possibleErrorMessages =
      List(
        s"unconfigured table $targetTable",
        s"table $targetTable does not exist"
      )
    possibleErrorMessages.contains(error.getMessage)
  }

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

  val keyColumnName: String =
    withPrefix("key")

  val valueColumnName: String =
    withPrefix("value")

  private def withDoubleQuotes(string: String) =
    "\"" + string + "\""

  private def withPrefix(columnName: String) =
    withDoubleQuotes(
      "_zflow_key_value_store_" + columnName
    )
}
