/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

import zio.flow.Operation.{ContraMap, Map}
import zio.flow.{ActivityError, Operation, OperationExecutor, Remote, RemoteContext}
import zio.schema.Schema
import zio.{Clock, Ref, Scope, ZIO}

case class MockedOperationExecutor private (mocks: Ref[MockedOperation]) extends OperationExecutor {
  override def execute[Input, Result](
    input: Input,
    operation: Operation[Input, Result]
  ): ZIO[RemoteContext, ActivityError, Result] =
    operation match {
      case ContraMap(inner, f, schema) =>
        val transformed: Remote[Any] = f(Remote(input)(operation.inputSchema.asInstanceOf[Schema[Input]]))
        RemoteContext
          .eval[Any](transformed)(schema.asInstanceOf[Schema[Any]])
          .mapError(executionError => ActivityError("Failed to transform input", Some(executionError.toException)))
          .flatMap { input2 =>
            execute(input2, inner.asInstanceOf[Operation[Any, Result]])
          }
      case Map(inner, f, schema) =>
        execute(input, inner).flatMap { result =>
          RemoteContext
            .eval[Any](
              f.asInstanceOf[Remote.UnboundRemoteFunction[Any, Any]](
                Remote(result)(inner.resultSchema.asInstanceOf[Schema[Any]])
              )
            )(schema.asInstanceOf[Schema[Any]])
            .mapBoth(
              executionError => ActivityError("Failed to transform output", Some(executionError.toException)),
              _.asInstanceOf[Result]
            )
        }
      case _ =>
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
