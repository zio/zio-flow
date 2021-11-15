package zio.flow.server

import java.io.IOException

import zio._
import zio.stream.ZStream

trait KeyValueStore {
  def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean]

  def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]]

  def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])]

}
