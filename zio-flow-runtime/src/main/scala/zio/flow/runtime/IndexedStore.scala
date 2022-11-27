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

package zio.flow.runtime

import zio._
import zio.flow.runtime.IndexedStore.Index
import zio.prelude.Subtype
import zio.schema.Schema
import zio.stream._

trait IndexedStore {
  def position(topic: String): IO[Throwable, Index]
  def put(topic: String, value: Chunk[Byte]): IO[Throwable, Index]
  def scan(topic: String, position: Index, until: Index): ZStream[Any, Throwable, Chunk[Byte]]
  def delete(topic: String): IO[Throwable, Unit]
}

object IndexedStore {
  type Index = Index.Type
  object Index extends Subtype[Long] {
    implicit val schema: Schema[Index] = Schema[Long].transform(Index(_), Index.unwrap)
  }

  implicit class IndexSyntax(private val index: Index) extends AnyVal {
    def next: Index = Index(index + 1)
  }

  def position(topic: String): ZIO[IndexedStore, Throwable, Index] =
    ZIO.serviceWithZIO(_.position(topic))

  def put(topic: String, value: Chunk[Byte]): ZIO[IndexedStore, Throwable, Index] =
    ZIO.serviceWithZIO(_.put(topic, value))

  def scan(topic: String, position: Index, until: Index): ZStream[IndexedStore, Throwable, Chunk[Byte]] =
    ZStream.serviceWithStream(_.scan(topic, position, until))

  def delete(topic: String): ZIO[IndexedStore, Throwable, Unit] =
    ZIO.serviceWithZIO(_.delete(topic))

  val inMemory: ZLayer[Any, Nothing, IndexedStore] =
    ZLayer {
      for {
        topics <- Ref.make[Map[String, Chunk[Chunk[Byte]]]](Map.empty)
      } yield InMemoryIndexedStore(topics)
    }

  private final case class InMemoryIndexedStore(topics: Ref[Map[String, Chunk[Chunk[Byte]]]]) extends IndexedStore {

    def position(topic: String): IO[Throwable, Index] =
      topics.get.map { topics =>
        topics.get(topic) match {
          case Some(values) => Index(values.length.toLong)
          case None         => Index(0L)
        }
      }

    def put(topic: String, value: Chunk[Byte]): IO[Throwable, Index] =
      topics.modify { topics =>
        topics.get(topic) match {
          case Some(values) => Index(values.length.toLong) -> topics.updated(topic, values :+ value)
          case None         => Index(0L)                   -> topics.updated(topic, Chunk(value))
        }
      }

    def scan(topic: String, position: Index, until: Index): ZStream[Any, Throwable, Chunk[Byte]] =
      ZStream.unwrap {
        topics.get.map { topics =>
          topics.get(topic) match {
            case Some(values) => ZStream.fromIterable(values.slice(position.toInt, until.toInt))
            case None         => ZStream.empty
          }
        }
      }

    override def delete(topic: String): IO[Throwable, Unit] =
      topics.update { topics =>
        topics - topic
      }
  }
}
