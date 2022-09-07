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

import zio.flow.{FlowId, PromiseId, RemoteVariableName}
import zio.schema.{DeriveSchema, Schema}

sealed trait ExecutorError { self =>
  def toMessage: String = self match {
    case ExecutorError.TypeError(of, message)              => s"Failed to convert $of to typed value: $message"
    case ExecutorError.RemoteEvaluationError(error)        => s"Failed to evaluate remote value: ${error.toMessage}"
    case ExecutorError.DeserializationError(of, message)   => s"Failed to deserialize $of: $message"
    case ExecutorError.MissingVariable(name, context)      => s"Could not find variable $name in $context"
    case ExecutorError.MissingPromiseResult(name, context) => s"Could not find promise result $name in $context"
    case ExecutorError.UnexpectedDynamicValue(details)     => s"Unexpected dynamic value: $details"
    case ExecutorError.InvalidOperationArguments(details)  => s"Invalid operation arguments: $details"
    case ExecutorError.AwaitedFlowDied(flowId, reason)     => s"Awaited flow ($flowId) died: ${reason.toMessage}"
    case ExecutorError.KeyValueStoreError(operation, reason) =>
      s"Key-value store failure in $operation: ${reason.getMessage}"
    case ExecutorError.LogError(error)           => s"Durable log failure: ${error.toMessage}"
    case ExecutorError.VariableChangeLogFinished => "Variable change log finished unexpectedly"
    case ExecutorError.FlowDied                  => s"Could not evaluate ZFlow"
  }

  def cause: Option[Throwable] = None

  def toException: Exception = ExecutorError.Exception(self)
}

object ExecutorError {
  final case class Exception(error: ExecutorError) extends RuntimeException(error.toMessage, error.cause.orNull)

  final case class TypeError(of: String, message: String)                       extends ExecutorError
  final case class RemoteEvaluationError(error: zio.flow.RemoteEvaluationError) extends ExecutorError
  final case class DeserializationError(of: String, message: String)            extends ExecutorError

  final case class MissingVariable(name: RemoteVariableName, context: String) extends ExecutorError
  final case class MissingPromiseResult(name: PromiseId, context: String)     extends ExecutorError
  final case class UnexpectedDynamicValue(details: String)                    extends ExecutorError
  final case class InvalidOperationArguments(details: String)                 extends ExecutorError
  final case class AwaitedFlowDied(flowId: FlowId, reason: ExecutorError) extends ExecutorError {
    override val cause: Option[Throwable] = Some(Exception(reason))
  }

  final case class KeyValueStoreError(operation: String, reason: Throwable) extends ExecutorError {
    override val cause: Option[Throwable] = Some(reason)
  }
  final case class LogError(error: DurableLogError) extends ExecutorError {
    override val cause: Option[Throwable] = error.cause
  }
  case object VariableChangeLogFinished extends ExecutorError

  case object FlowDied extends ExecutorError

  import zio.flow.schemaThrowable
  implicit val schema: Schema[ExecutorError] = DeriveSchema.gen
}
