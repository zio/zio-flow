package zio.flow.server

import java.io.IOException

import zio._ 

trait KeyValueStore {
  def put(table: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean]

  def get(table: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]]
}