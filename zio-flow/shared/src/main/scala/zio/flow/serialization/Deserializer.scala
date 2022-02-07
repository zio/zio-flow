package zio.flow.serialization

import zio.Chunk
import zio.schema.Schema
import zio.schema.codec.{JsonCodec, ProtobufCodec}

trait Deserializer {
  def deserialize[A](value: Chunk[Byte])(implicit schema: Schema[A]): Either[String, A]
}

object Deserializer {

  val json: Deserializer = new Deserializer {
    override def deserialize[A](value: Chunk[Byte])(implicit schema: Schema[A]): Either[String, A] =
      JsonCodec.decode(schema)(value)
  }

  val protobuf: Deserializer = new Deserializer {
    override def deserialize[A](value: Chunk[Byte])(implicit schema: Schema[A]): Either[String, A] =
      ProtobufCodec.decode(schema)(value)
  }
}
