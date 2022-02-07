package zio.flow.serialization

import zio.Chunk
import zio.schema.Schema
import zio.schema.codec.{JsonCodec, ProtobufCodec}

trait Serializer {
  def serialize[A](value: A)(implicit schema: Schema[A]): Chunk[Byte]
}

object Serializer {

  val json: Serializer = new Serializer {
    override def serialize[A](value: A)(implicit schema: Schema[A]): Chunk[Byte] =
      JsonCodec.encode(schema)(value)
  }

  val protobuf: Serializer = new Serializer {
    override def serialize[A](value: A)(implicit schema: Schema[A]): Chunk[Byte] =
      ProtobufCodec.encode(schema)(value)
  }
}
