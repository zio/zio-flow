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

import zio.flow._
import zio.schema.{DeriveSchema, Schema}

final case class ScopedFlowId(name: FlowId, parentStack: List[FlowId]) {
  lazy val parent: Option[ScopedFlowId] =
    parentStack match {
      case ::(head, next) => Some(ScopedFlowId(head, next))
      case Nil            => None
    }

  lazy val asFlowId: FlowId = {
    parent match {
      case Some(parent) => parent.asFlowId / name
      case None         => name
    }
  }

  lazy val asScope: RemoteVariableScope =
    parent match {
      case Some(parent) => RemoteVariableScope.Fiber(name, parent.asScope)
      case None         => RemoteVariableScope.TopLevel(name)
    }

  lazy val asString: String = FlowId.unwrap(asFlowId)

  def child(childName: FlowId): ScopedFlowId =
    ScopedFlowId(childName, name :: parentStack)
}

object ScopedFlowId {
  implicit val schema: Schema[ScopedFlowId] = DeriveSchema.gen

  def toplevel(name: FlowId): ScopedFlowId = ScopedFlowId(name, Nil)
}
