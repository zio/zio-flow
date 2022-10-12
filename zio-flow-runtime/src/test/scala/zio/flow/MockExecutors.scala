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

import zio.flow.mock.{MockedOperation, MockedOperationExecutor}
import zio.flow.runtime.internal.PersistentExecutor
import zio.flow.runtime.{DurableLog, KeyValueStore, ZFlowExecutor}
import zio.flow.serialization.{Deserializer, Serializer}
import zio.{Scope, ZIO, ZLayer}

object MockExecutors {
  def persistent(
    mockedOperations: MockedOperation = MockedOperation.Empty
  ): ZIO[Scope with DurableLog with KeyValueStore with Configuration, Nothing, ZFlowExecutor] =
    MockedOperationExecutor.make(mockedOperations).flatMap { operationExecutor =>
      ((DurableLog.any ++ KeyValueStore.any ++ Configuration.any ++ ZLayer.succeed(operationExecutor) ++ ZLayer
        .succeed(Serializer.json) ++ ZLayer.succeed(Deserializer.json)) >>>
        PersistentExecutor
          .make()).build.map(_.get[ZFlowExecutor])
    }
}
