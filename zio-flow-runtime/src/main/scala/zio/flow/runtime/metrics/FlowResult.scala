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

package zio.flow.runtime.metrics

sealed trait FlowResult

object FlowResult {
  case object Success extends FlowResult

  case object Failure extends FlowResult

  case object Death extends FlowResult

  def toLabel(result: FlowResult): String =
    result match {
      case FlowResult.Success => "success"
      case FlowResult.Failure => "failure"
      case FlowResult.Death   => "death"
    } // TODO: categorize failures (introduce a proper error type first in place of IOException)
}
