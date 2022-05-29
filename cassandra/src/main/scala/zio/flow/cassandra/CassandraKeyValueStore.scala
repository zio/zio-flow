// TODO
//package zio.flow.cassandra
//
//import com.datastax.oss.driver.api.core.cql._
//import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
//import com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal
//import com.datastax.oss.driver.api.querybuilder.delete.DeleteSelection
//import com.datastax.oss.driver.api.querybuilder.insert.InsertInto
//import com.datastax.oss.driver.api.querybuilder.select.SelectFrom
//import com.datastax.oss.driver.api.querybuilder.{Literal, QueryBuilder}
//import zio.flow.cassandra.CassandraKeyValueStore._
//import zio.flow.internal.KeyValueStore
//import zio.flow.internal.KeyValueStore.Item
//import zio.stream.ZStream
//import zio.{Chunk, IO, Task, URLayer, ZIO, ZLayer}
//
//import java.io.IOException
//import java.nio.ByteBuffer
//
//final class CassandraKeyValueStore(session: CqlSession) extends KeyValueStore {
//
//  private val keyspace =
//    session.getKeyspace
//      .orElse(null: CqlIdentifier) // scalafix:ok DisableSyntax.null
//
//  private val cqlSelect: SelectFrom =
//    QueryBuilder.selectFrom(keyspace, table)
//
//  private val cqlInsert: InsertInto =
//    QueryBuilder.insertInto(keyspace, table)
//
//  private val cqlDelete: DeleteSelection =
//    QueryBuilder.deleteFrom(keyspace, table)
//
//  override def put(
//    namespace: String,
//    key: Chunk[Byte],
//    value: Chunk[Byte]
//  ): IO[IOException, Boolean] = {
//    val insert = toInsert(Item(namespace, key, value))
//
//    executeAsync(insert)
//      .mapBoth(
//        refineToIOException(s"Error putting key-value pair for <$namespace> namespace"),
//        _ => true
//      )
//  }
//
//  override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] = {
//    val query = cqlSelect
//      .column(valueColumnName)
//      .whereColumn(namespaceColumnName)
//      .isEqualTo(
//        literal(namespace)
//      )
//      .whereColumn(keyColumnName)
//      .isEqualTo(
//        byteBufferFrom(key)
//      )
//      .limit(1)
//      .build
//
//    executeAsync(query).flatMap { result =>
//      if (result.remaining > 0)
//        ZIO.attempt {
//          Option(blobValueOf(valueColumnName, result.one))
//        }
//      else
//        ZIO.none
//    }.mapError(
//      refineToIOException(s"Error retrieving or reading value for <$namespace> namespace")
//    )
//  }
//
//  override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] = {
//    val query = cqlSelect
//      .column(keyColumnName)
//      .column(valueColumnName)
//      .whereColumn(namespaceColumnName)
//      .isEqualTo(
//        literal(namespace)
//      )
//      .build
//
//    lazy val errorContext =
//      s"Error scanning all key-value pairs for <$namespace> namespace"
//
//    ZStream
//      .paginateZIO(
//        executeAsync(query)
//      )(_.map { result =>
//        val pairs =
//          ZStream
//            .fromJavaIterator(
//              result.currentPage.iterator
//            )
//            .mapZIO { row =>
//              ZIO.attempt {
//                blobValueOf(keyColumnName, row) -> blobValueOf(valueColumnName, row)
//              }
//            }
//            .mapError(
//              refineToIOException(errorContext)
//            )
//
//        val nextPage =
//          if (result.hasMorePages)
//            Option(
//              ZIO.fromCompletionStage(result.fetchNextPage())
//            )
//          else
//            None
//
//        (pairs, nextPage)
//      })
//      .mapError(
//        refineToIOException(errorContext)
//      )
//      .flatten
//  }
//
//  override def delete(namespace: String, key: Chunk[Byte]): IO[IOException, Unit] = {
//    val delete = cqlDelete
//      .column(valueColumnName)
//      .whereColumn(namespaceColumnName)
//      .isEqualTo(
//        literal(namespace)
//      )
//      .whereColumn(keyColumnName)
//      .isEqualTo(
//        byteBufferFrom(key)
//      )
//      .build
//
//    executeAsync(delete)
//      .mapBoth(
//        refineToIOException(s"Error deleting key-value pair from <$namespace> namespace"),
//        _ => ()
//      )
//  }
//
//  override def putAll(items: Chunk[KeyValueStore.Item]): IO[IOException, Unit] = {
//    val batch = new BatchStatementBuilder(BatchType.LOGGED)
//    batch.addStatements(
//      items.map(toInsert): _*
//    )
//    executeAsync(batch.build())
//      .mapBoth(
//        refineToIOException(s"Error putting multiple key-value pairs"),
//        _ => ()
//      )
//  }
//
//  private def toInsert(item: KeyValueStore.Item): SimpleStatement =
//    cqlInsert
//      .value(
//        namespaceColumnName,
//        literal(item.namespace)
//      )
//      .value(
//        keyColumnName,
//        byteBufferFrom(item.key)
//      )
//      .value(
//        valueColumnName,
//        byteBufferFrom(item.value)
//      )
//      .build
//
//  private def executeAsync(statement: Statement[_]): Task[AsyncResultSet] =
//    ZIO.fromCompletionStage(
//      session.executeAsync(statement)
//    )
//}
//
//object CassandraKeyValueStore {
//
//  val layer: URLayer[CqlSession, KeyValueStore] =
//    ZLayer {
//      ZIO
//        .service[CqlSession]
//        .map(new CassandraKeyValueStore(_))
//    }
//
//  private[cassandra] val tableName: String =
//    withDoubleQuotes("_zflow_key_value_store")
//
//  private[cassandra] val namespaceColumnName: String =
//    withColumnPrefix("namespace")
//
//  private[cassandra] val keyColumnName: String =
//    withColumnPrefix("key")
//
//  private[cassandra] val valueColumnName: String =
//    withColumnPrefix("value")
//
//  private val table: CqlIdentifier =
//    CqlIdentifier.fromCql(tableName)
//
//  private def byteBufferFrom(bytes: Chunk[Byte]): Literal =
//    literal(
//      ByteBuffer.wrap(bytes.toArray)
//    )
//
//  private def blobValueOf(columnName: String, row: Row): Chunk[Byte] =
//    Chunk.fromArray(
//      row
//        .getByteBuffer(columnName)
//        .array
//    )
//
//  private def refineToIOException(errorContext: String): Throwable => IOException = {
//    case error: IOException =>
//      error
//    case error =>
//      new IOException(s"$errorContext: <${error.getMessage}>.", error)
//  }
//
//  private def withColumnPrefix(s: String) =
//    withDoubleQuotes("zflow_kv_" + s)
//
//  private def withDoubleQuotes(string: String) =
//    "\"" + string + "\""
//}
