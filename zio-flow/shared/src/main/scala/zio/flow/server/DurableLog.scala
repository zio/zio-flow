package zio.flow.server

import java.io.IOException

import zio._
import zio.stream._

trait DurableLog {
  def append(topic: String, value: Chunk[Byte]): IO[IOException, Long]

  def subscribe(topic: String, position: Long): ZStream[Any, IOException, Chunk[Byte]]
}
