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

package zio.flow.mock

import zio.flow.{ActivityError, Operation, OperationExecutor}
import zio.{Clock, Ref, Scope, ZIO}

case class MockedOperationExecutor private (mocks: Ref[MockedOperation]) extends OperationExecutor[Any] {
  override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Any, ActivityError, A] =
    mocks.modify { mock =>
      mock.matchOperation(operation, input)
    }.flatMap {
      case Some(MockedOperation.Match(result, duration)) =>
        for {
          _ <- ZIO.logInfo(s"Simulating operation $operation with input $input")
          _ <- Clock.sleep(duration)
        } yield result
      case None =>
        ZIO.logWarning(s"Mocked operation $operation with input $input was not found") *>
          ZIO.fail(ActivityError(s"Operation $operation not found", None))
    }
}

object MockedOperationExecutor {
  def make(mock: MockedOperation): ZIO[Scope, Nothing, MockedOperationExecutor] =
    Ref.make(mock).flatMap { ref =>
      val opExecutor = new MockedOperationExecutor(ref)
      ZIO.addFinalizer {
        ref.get.flatMap { lastMock =>
          ZIO
            .dieMessage(s"Some of the mocked operation expectations did not met: $lastMock")
            .unless(
              lastMock == MockedOperation.Empty ||
                lastMock.isInstanceOf[MockedOperation.Repeated]
            )
        }
      }.as(opExecutor)
    }
}
