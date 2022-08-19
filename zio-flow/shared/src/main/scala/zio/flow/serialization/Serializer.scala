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
