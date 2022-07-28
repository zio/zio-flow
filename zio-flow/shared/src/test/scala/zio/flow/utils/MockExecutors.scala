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

package zio.flow.utils

import zio._
import zio.flow.internal.{DurableLog, KeyValueStore, PersistentExecutor, ZFlowExecutor}
import zio.flow.mock.{MockedOperation, MockedOperationExecutor}
import zio.flow.serialization.{Deserializer, Serializer}

object MockExecutors {
  def persistent(
    mockedOperations: MockedOperation = MockedOperation.Empty
  ): ZIO[Scope with DurableLog with KeyValueStore, Nothing, ZFlowExecutor] =
    MockedOperationExecutor.make(mockedOperations).flatMap { operationExecutor =>
      PersistentExecutor
        .make(
          operationExecutor,
          Serializer.json,
          Deserializer.json
        )
        .build
        .map(_.get[ZFlowExecutor])
    }
}
