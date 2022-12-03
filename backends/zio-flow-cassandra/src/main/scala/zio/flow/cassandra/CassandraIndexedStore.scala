/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.cassandra

import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, Row, Statement}
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal
import com.datastax.oss.driver.api.querybuilder.delete.DeleteSelection
import com.datastax.oss.driver.api.querybuilder.insert.InsertInto
import com.datastax.oss.driver.api.querybuilder.select.SelectFrom
import com.datastax.oss.driver.api.querybuilder.update.UpdateStart
import com.datastax.oss.driver.api.querybuilder.{Literal, QueryBuilder}
import zio.flow.runtime.IndexedStore
import zio.flow.runtime.IndexedStore.Index
import zio.schema.Schema
import zio.schema.codec.ProtobufCodec
import zio.stream.ZStream
import zio.{Chunk, IO, Schedule, Task, URLayer, ZIO, ZLayer}

import java.io.IOException
import java.nio.ByteBuffer
import scala.jdk.CollectionConverters._

final class CassandraIndexedStore(session: CqlSession) extends IndexedStore {
  import CassandraIndexedStore._

  private val keyspace =
    session.getKeyspace
      .orElse(null: CqlIdentifier) // scalafix:ok DisableSyntax.null

  private val cqlSelect: SelectFrom =
    QueryBuilder.selectFrom(keyspace, table)

  private val cqlInsert: InsertInto =
    QueryBuilder.insertInto(keyspace, table)

  private val cqlUpdate: UpdateStart =
    QueryBuilder.update(keyspace, table)

  private val cqlDelete: DeleteSelection =
    QueryBuilder.deleteFrom(keyspace, table)

  override def position(topic: String): IO[Throwable, Index] =
    executeAsync(
      cqlSelect
        .column(valueColumnName)
        .whereColumn(topicColumnName)
        .isEqualTo(literal(topic))
        .whereColumn(indexColumnName)
        .isEqualTo(literal(-1L))
        .limit(1)
        .build()
    ).mapError(
      new IOException(s"Failed to get index of topic <$topic>", _)
    ).flatMap { result =>
      if (result.remaining > 0) {
        ZIO
          .fromEither(ProtobufCodec.decode(Schema[Long])(blobValueOf(valueColumnName, result.one())))
          .mapBoth(
            error => new IOException(s"Failed to decode stored position of topic $topic: $error"),
            Index(_)
          )
      } else {
        ZIO.succeed(Index(0L))
      }
    }

  override def put(topic: String, value: Chunk[Byte]): IO[Throwable, Index] =
    (for {
      currentIndex <- position(topic).mapError(Some(_))
      nextIndex     = currentIndex.next
      _ <- if (currentIndex == Index(0L)) {
             val insertPosition =
               cqlInsert
                 .value(topicColumnName, literal(topic))
                 .value(indexColumnName, literal(-1L))
                 .value(valueColumnName, byteBufferFrom(ProtobufCodec.encode(Schema[Long])(nextIndex.toLong)))
                 .ifNotExists()
                 .build()
             for {
               posInsertResult <- executeAsync(insertPosition).mapError(Some(_))
               _               <- ZIO.fail(None).unless(posInsertResult.wasApplied())
             } yield ()
           } else {
             // Updating position first
             val updatePosition =
               cqlUpdate
                 .setColumn(valueColumnName, byteBufferFrom(ProtobufCodec.encode(Schema[Long])(nextIndex.toLong)))
                 .whereColumn(topicColumnName)
                 .isEqualTo(literal(topic))
                 .whereColumn(indexColumnName)
                 .isEqualTo(literal(-1L))
                 .ifColumn(valueColumnName)
                 .isEqualTo(byteBufferFrom(ProtobufCodec.encode(Schema[Long])(currentIndex.toLong)))
                 .build()
             for {
               posUpdateResult <- executeAsync(updatePosition).mapError(Some(_))
               _               <- ZIO.fail(None).unless(posUpdateResult.wasApplied())
             } yield ()
           }
    } yield nextIndex)
      .retry(Schedule.recurWhile {
        case None    => true
        case Some(_) => false
      })
      .flatMap { nextIndex =>
        executeAsync(
          cqlInsert
            .value(topicColumnName, literal(topic))
            .value(indexColumnName, literal(nextIndex.toLong))
            .value(valueColumnName, byteBufferFrom(value))
            .build()
        ).mapBoth(Some(_), _ => nextIndex)
      }
      .mapError {
        case None        => new IllegalStateException(s"Illegal state in CassandraIndexedStore#put")
        case Some(error) => new IOException(s"Failed to put new value into topic <$topic>", error)
      }

  override def scan(topic: String, position: Index, until: Index): ZStream[Any, Throwable, Chunk[Byte]] =
    // TODO: extract
    ZStream
      .paginateZIO(
        executeAsync(
          cqlSelect
            .column(valueColumnName)
            .whereColumn(topicColumnName)
            .isEqualTo(literal(topic))
            .whereColumn(indexColumnName)
            .isGreaterThanOrEqualTo(literal(position.toLong))
            .whereColumn(indexColumnName)
            .isLessThanOrEqualTo(literal(until.toLong))
            .build()
        )
      )(_.map { result =>
        val pairs =
          ZStream
            .fromJavaIterator(
              result.currentPage.iterator
            )
            .mapZIO { row =>
              ZIO.attempt {
                blobValueOf(valueColumnName, row)
              }
            }

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
        new IOException(s"Error scanning topic <$topic>", _)
      )
      .flatten

  override def delete(topic: String): IO[Throwable, Unit] =
    executeAsync(
      cqlDelete
        .whereColumn(topicColumnName)
        .isEqualTo(literal(topic))
        .build()
    )
      .mapBoth(
        new IOException(s"Error deleting topic <$topic>", _),
        _ => ()
      )

  private def executeAsync(statement: Statement[_]): Task[AsyncResultSet] =
    ZIO.fromCompletionStage(
      session.executeAsync(statement)
    )
}

object CassandraIndexedStore {
  val layer: ZLayer[Any, Throwable, IndexedStore] =
    ZLayer.scoped {
      for {
        config <- ZIO.config(CassandraConfig.config.nested("cassandra-indexed-store"))
        session <- ZIO.acquireRelease {
                     ZIO.fromCompletionStage(
                       CqlSession.builder
                         .addContactPoints(config.contactPoints.asJava)
                         .withKeyspace(
                           CqlIdentifier.fromCql(config.keyspace)
                         )
                         .withLocalDatacenter(config.localDatacenter)
                         .buildAsync()
                     )
                   } { session =>
                     ZIO.attemptBlocking {
                       session.close()
                     }.orDie
                   }
      } yield new CassandraIndexedStore(session)
    }

  val fromSession: URLayer[CqlSession, IndexedStore] =
    ZLayer {
      ZIO
        .service[CqlSession]
        .map(new CassandraIndexedStore(_))
    }

  private[cassandra] val tableName: String =
    withDoubleQuotes("_zflow_idx_store")

  private[cassandra] val topicColumnName: String =
    withColumnPrefix("topic")

  private[cassandra] val indexColumnName: String =
    withColumnPrefix("index")

  private[cassandra] val valueColumnName: String =
    withColumnPrefix("value")

  private val table: CqlIdentifier =
    CqlIdentifier.fromCql(tableName)

  // TODO: unify helpers
  private def withColumnPrefix(s: String) =
    withDoubleQuotes("zflow_idx_" + s)

  private def withDoubleQuotes(string: String) =
    "\"" + string + "\""

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

}
