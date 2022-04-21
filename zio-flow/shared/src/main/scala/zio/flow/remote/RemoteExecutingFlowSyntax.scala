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

package zio.flow.remote

import zio.flow.{ActivityError, ExecutingFlow, FlowId, Remote, ZFlow}
import zio.schema.Schema

class RemoteExecutingFlowSyntax[E, A](self: Remote[ExecutingFlow[E, A]]) {

  def flowId: Remote[FlowId] = ???

  def await(implicit schemaE: Schema[E], schemaA: Schema[A]): ZFlow[Any, ActivityError, Either[E, A]] =
    ZFlow.Await(self.widen[ExecutingFlow[E, A]])

  def interrupt: ZFlow[Any, ActivityError, Any] =
    ZFlow.Interrupt(self.widen[ExecutingFlow[E, A]])

}
