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
      JsonCodec.decode(schema)(value).left.map(_.message)
  }

  val protobuf: Deserializer = new Deserializer {
    override def deserialize[A](value: Chunk[Byte])(implicit schema: Schema[A]): Either[String, A] =
      ProtobufCodec.decode(schema)(value).left.map(_.message)
  }
}
