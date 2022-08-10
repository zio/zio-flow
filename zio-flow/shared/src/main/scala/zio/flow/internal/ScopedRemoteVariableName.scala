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

package zio.flow.internal

import zio.Chunk
import zio.flow.{FlowId, RemoteVariableName, TransactionId}
import zio.schema.{DeriveSchema, Schema}

final case class ScopedRemoteVariableName(name: RemoteVariableName, scope: RemoteVariableScope) {
  def asString: String = getScopePrefix(scope) + name

  private def getScopePrefix(scope: RemoteVariableScope): String =
    scope match {
      case RemoteVariableScope.TopLevel(flowId) =>
        FlowId.unwrap(flowId) + "__"
      case RemoteVariableScope.Fiber(flowId, parent) =>
        getScopePrefix(parent) + "fi" ++ FlowId.unwrap(flowId) + "__"
      case RemoteVariableScope.Transactional(parent, transaction) =>
        getScopePrefix(parent) + "tx" ++ TransactionId.unwrap(transaction) + "__"
    }
}

object ScopedRemoteVariableName {
  def fromString(value: String): Option[ScopedRemoteVariableName] = {
    val parts = Chunk.fromArray(value.split("__"))
    if (parts.size < 2)
      None
    else {
      val name = RemoteVariableName.unsafeMake(parts(parts.size - 1))
      val scope =
        parts.tail.init
          .foldLeft[Option[RemoteVariableScope]](Some(RemoteVariableScope.TopLevel(FlowId.unsafeMake(parts(0))))) {
            case (Some(scope), current) =>
              if (current.startsWith("tx")) {
                Some(RemoteVariableScope.Transactional(scope, TransactionId.unsafeMake(current.drop(2))))
              } else if (current.startsWith("fi")) {
                Some(RemoteVariableScope.Fiber(FlowId.unsafeMake(current.drop(2)), scope))
              } else {
                None
              }
            case _ => None
          }
      scope.map(scope => ScopedRemoteVariableName(name, scope))
    }
  }

  implicit val schema: Schema[ScopedRemoteVariableName] = DeriveSchema.gen
}
