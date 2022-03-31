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

import java.time.Duration
import zio._
import zio.flow.ExecutingFlow.InMemoryExecutingFlow
import zio.flow.Remote.{===>, RemoteFunction}
import zio.flow.serialization.{Deserializer, Serializer}
import zio.flow.{
  ActivityError,
  ExecutingFlow,
  ExecutionEnvironment,
  OperationExecutor,
  Remote,
  RemoteContext,
  RemoteVariableName,
  SchemaAndValue,
  SchemaOrNothing,
  ZFlow
}
import zio.schema._

import java.io.IOException

trait ZFlowExecutor[-U] {
  def submit[E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](uniqueId: U, flow: ZFlow[Any, E, A]): IO[E, A]
}

object ZFlowExecutor {


}
