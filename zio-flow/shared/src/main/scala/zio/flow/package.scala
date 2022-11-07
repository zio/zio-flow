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

package zio

import zio.flow.FlowId.unwrap
import zio.prelude.Assertion._
import zio.prelude.Newtype
import zio.schema.Schema

import java.nio.charset.StandardCharsets
import java.util.UUID

package object flow extends Syntax with Schemas with InstantModule { self =>
  type RemoteVariableName = RemoteVariableName.Type

  private[flow] val syntax: Syntax = self

  object RemoteVariableName extends Newtype[String] {
    implicit val schema: Schema[RemoteVariableName] = Schema[String].transform(wrap(_), unwrap)

    override def assertion = assert {
      !contains("__")
    }

    def unsafeMake(name: String): RemoteVariableName = wrap(name)
  }

  type BindingName = BindingName.Type
  object BindingName extends Newtype[UUID] {
    implicit val schema: Schema[BindingName] = Schema[UUID].transform(wrap(_), unwrap)
  }

  type FlowId = FlowId.Type
  object FlowId extends Newtype[String] {
    implicit val schema: Schema[FlowId] = Schema[String].transform(wrap(_), unwrap)

    override def assertion = assert {
      !contains("__")
    }

    def unsafeMake(name: String): FlowId = wrap(name)

    def newRandom: ZIO[Any, Nothing, FlowId] =
      zio.Random.nextUUID.map(rid => wrap(rid.toString))
  }

  implicit class FlowIdSyntax(val flowId: FlowId) extends AnyVal {
    def /(postfix: FlowId): FlowId = FlowId.unsafeMake(unwrap(flowId) + "_" + unwrap(postfix))

    def toRaw: Chunk[Byte] = Chunk.fromArray(unwrap(flowId).getBytes(StandardCharsets.UTF_8))
  }

  type TransactionId = TransactionId.Type
  object TransactionId extends Newtype[String] {
    implicit val schema: Schema[TransactionId] = Schema[String].transform(wrap(_), unwrap)

    override def assertion = assert {
      !contains("__")
    }

    def fromCounter(counter: Int): TransactionId =
      wrap(counter.toString)

    def unsafeMake(name: String): TransactionId =
      wrap(name)
  }

  type RecursionId = RecursionId.Type
  object RecursionId extends Newtype[UUID] {
    implicit val schema: Schema[RecursionId] = Schema[UUID].transform(wrap(_), unwrap)
  }

  implicit class RecursionIdSyntax(val recursionId: RecursionId) extends AnyVal {
    def toRemoteVariableName: RemoteVariableName =
      RemoteVariableName.unsafeMake(s"_!rec!_${RecursionId.unwrap(recursionId)}")
  }

  type PromiseId = PromiseId.Type
  object PromiseId extends Newtype[String] {
    implicit val schema: Schema[PromiseId] = Schema[String].transform(wrap(_), unwrap)
  }

  type ConfigKey = ConfigKey.Type

  object ConfigKey extends Newtype[String] {
    implicit val schema: Schema[ConfigKey] = Schema[String].transform(wrap(_), unwrap)
  }
}
