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

import zio.prelude.Newtype
import zio.schema.Schema

import java.nio.charset.StandardCharsets
import java.util.UUID

package object flow extends Syntax with Schemas with InstantModule with OffsetDateTimeModule with FlowPackageVersionSpecific { self =>
  private[flow] val syntax: Syntax = self

  implicit class FlowIdSyntax(val flowId: FlowId) extends AnyVal {
    def /(postfix: FlowId): FlowId = FlowId.unsafeMake(FlowId.unwrap(flowId) + "_" + FlowId.unwrap(postfix))

    def toRaw: Chunk[Byte] = Chunk.fromArray(FlowId.unwrap(flowId).getBytes(StandardCharsets.UTF_8))
  }

  type BindingName = BindingName.Type
  object BindingName extends Newtype[UUID] {
    implicit val schema: Schema[BindingName] = Schema[UUID].transform(wrap(_), unwrap)
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
