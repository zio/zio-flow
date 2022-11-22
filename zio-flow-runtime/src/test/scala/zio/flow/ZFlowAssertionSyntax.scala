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

import zio.flow.mock.MockedOperation
import zio.flow.runtime.{DurableLog, ExecutorError, KeyValueStore, ZFlowExecutor}
import zio.schema.{DynamicValue, Schema}
import zio.{Duration, Fiber, Scope, ZIO, durationInt}

object ZFlowAssertionSyntax {

  implicit final class InMemoryZFlowAssertion[E, A](private val zflow: ZFlow[Any, E, A]) {
    def evaluateTestPersistent(
      id: String,
      mock: MockedOperation = MockedOperation.Empty,
      gcPeriod: Duration = 5.minutes
    )(implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[DurableLog with KeyValueStore with Configuration, E, A] =
      ZIO.scoped {
        submitTestPersistent(id, mock, gcPeriod).flatMap(_._2.join)
      }

    def submitTestPersistent(id: String, mock: MockedOperation = MockedOperation.Empty, gcPeriod: Duration = 5.minutes)(
      implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[Scope with DurableLog with KeyValueStore with Configuration, E, (ZFlowExecutor, Fiber[E, A])] =
      MockExecutors.persistent(mock, gcPeriod).flatMap { executor =>
        executor.restartAll().orDieWith(_.toException) *>
          executor.run(FlowId.unsafeMake(id), zflow).forkScoped.map(fiber => (executor, fiber))
      }

    // Submit a flow and wait for the result via the start+poll interface
    def evaluateTestStartAndPoll(
      id: String,
      waitBeforePoll: Duration
    ): ZIO[DurableLog with KeyValueStore with Configuration, ExecutorError, Option[
      Either[Either[ExecutorError, DynamicValue], DynamicValue]
    ]] =
      ZIO.scoped {
        val fId = FlowId.unsafeMake(id)
        MockExecutors.persistent().flatMap { executor =>
          executor.restartAll().orDieWith(_.toException) *>
            executor.start(fId, zflow) *>
            ZIO.sleep(waitBeforePoll) *>
            executor.poll(fId)
        }
      }
  }
}
