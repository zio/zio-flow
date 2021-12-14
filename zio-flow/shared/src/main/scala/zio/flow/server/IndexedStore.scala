package zio.flow.server

import java.io.IOException

import zio._
import zio.stream._

trait IndexedStore {
  def position(topic: String): IO[IOException, Long]
  def put(topic: String, value: Chunk[Byte]): IO[IOException, Long]
  def scan(topic: String, position: Long, until: Long): ZStream[Any, IOException, Chunk[Byte]]
}

object IndexedStore {

  def position(topic: String): ZIO[Has[IndexedStore], IOException, Long] =
    ZIO.serviceWith(_.position(topic))

  def put(topic: String, value: Chunk[Byte]): ZIO[Has[IndexedStore], IOException, Long] =
    ZIO.serviceWith(_.put(topic, value))

  def scan(topic: String, position: Long, until: Long): ZStream[Has[IndexedStore], IOException, Chunk[Byte]] =
    ZStream.serviceWithStream(_.scan(topic, position, until))

  val live: ZLayer[Any, Nothing, Has[IndexedStore]] =
    ZLayer.fromManaged {
      for {
        topics      <- Ref.makeManaged[Map[String, Chunk[Chunk[Byte]]]](Map.empty)
        indexedStore = IndexedStoreLive(topics)
      } yield indexedStore
    }

  private final case class IndexedStoreLive(topics: Ref[Map[String, Chunk[Chunk[Byte]]]]) extends IndexedStore {

    def position(topic: String): IO[IOException, Long] =
      topics.get.map { topics =>
        topics.get(topic) match {
          case Some(values) => values.length.toLong
          case None         => 0L
        }
      }

    def put(topic: String, value: Chunk[Byte]): IO[IOException, Long] =
      topics.modify { topics =>
        topics.get(topic) match {
          case Some(values) => values.length.toLong -> topics.updated(topic, values :+ value)
          case None         => 0L                   -> topics.updated(topic, Chunk(value))
        }
      }

    def scan(topic: String, position: Long, until: Long): ZStream[Any, IOException, Chunk[Byte]] =
      ZStream.unwrap {
        topics.get.map { topics =>
          topics.get(topic) match {
            case Some(values) => ZStream.fromIterable(values.slice(position.toInt, until.toInt))
            case None         => ZStream.empty
          }
        }
      }
  }
}
