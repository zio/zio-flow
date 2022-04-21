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
import zio.prelude.Newtype
import zio.schema.Schema

import java.nio.charset.StandardCharsets

package object flow extends Syntax with Schemas {
  type RemoteVariableName = RemoteVariableName.Type
  object RemoteVariableName extends Newtype[String] {
    implicit val schema: Schema[RemoteVariableName] = Schema[String].transform(apply(_), unwrap)
  }

  implicit class RemoteVariableNameSyntax(val name: RemoteVariableName) extends AnyVal {
    def prefixedBy(flowId: FlowId): String =
      FlowId.unwrap(flowId) + RemoteVariableName.unwrap(name)
  }

  type RemoteVariableVersion = RemoteVariableVersion.Type
  object RemoteVariableVersion extends Newtype[Long] {
    implicit val schema: Schema[RemoteVariableVersion] = Schema[Long].transform(apply(_), unwrap)
  }

  implicit class RemoteVariableVersionSyntax(val version: RemoteVariableVersion) extends AnyVal {
    def increment: RemoteVariableVersion = RemoteVariableVersion(RemoteVariableVersion.unwrap(version) + 1)
  }

  object FlowId extends Newtype[String]
  type FlowId = FlowId.Type

  implicit class FlowIdSyntax(val flowId: FlowId) extends AnyVal {
    def +(postfix: String): FlowId = FlowId(unwrap(flowId) + postfix)
    def toRaw: Chunk[Byte]         = Chunk.fromArray(unwrap(flowId).getBytes(StandardCharsets.UTF_8))
  }

  type TransactionId = TransactionId.Type
  object TransactionId extends Newtype[String] {
    implicit val schema: Schema[TransactionId] = Schema[String].transform(apply(_), unwrap)
  }
}
