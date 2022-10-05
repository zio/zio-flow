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

package zio.flow

import zio.flow.internal.ExecutorError
import zio.schema.{DeriveSchema, Schema}

sealed trait RemoteEvaluationError { self =>

  def toMessage: String = self match {
    case RemoteEvaluationError.VariableNotFound(name)          => s"Could not find identifier $name"
    case RemoteEvaluationError.BindingNotFound(id)             => s"Cannot evaluate binding $id, it has to be substituted"
    case RemoteEvaluationError.UnexpectedDynamicValue(details) => s"Unexpected dynamic value: $details"
    case RemoteEvaluationError.RecursionNotFound(id)           => s"Could not find recursion body $id"
    case RemoteEvaluationError.TypeError(message)              => s"Type error: $message"
    case RemoteEvaluationError.RemoteContextError(error)       => error.toMessage
  }
}

object RemoteEvaluationError {
  final case class VariableNotFound(name: RemoteVariableName) extends RemoteEvaluationError
  final case class BindingNotFound(id: BindingName)           extends RemoteEvaluationError
  final case class UnexpectedDynamicValue(details: String)    extends RemoteEvaluationError
  final case class RecursionNotFound(id: RecursionId)         extends RemoteEvaluationError
  final case class TypeError(message: String)                 extends RemoteEvaluationError
  final case class RemoteContextError(error: ExecutorError)   extends RemoteEvaluationError

  implicit val schema: Schema[RemoteEvaluationError] = DeriveSchema.gen
}
