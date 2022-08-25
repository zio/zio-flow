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
import zio.flow.internal.PersistentExecutor.FlowResult
import zio.flow.internal.{DurableLog, KeyValueStore, ZFlowExecutor}
import zio.flow.mock.MockedOperation
import zio.flow.{FlowId, ZFlow}
import zio.schema.{DynamicValue, Schema}

object ZFlowAssertionSyntax {

  implicit final class InMemoryZFlowAssertion[E, A](private val zflow: ZFlow[Any, E, A]) {
    def evaluateTestPersistent(id: String, mock: MockedOperation = MockedOperation.Empty)(implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[DurableLog with KeyValueStore, E, A] =
      ZIO.scoped {
        submitTestPersistent(id, mock).flatMap(_._2.join)
      }

    def submitTestPersistent(id: String, mock: MockedOperation = MockedOperation.Empty)(implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[Scope with DurableLog with KeyValueStore, E, (ZFlowExecutor, Fiber[E, A])] =
      MockExecutors.persistent(mock).flatMap { executor =>
        executor.restartAll().orDie *>
          executor.submit(FlowId.unsafeMake(id), zflow).forkScoped.map(fiber => (executor, fiber))
      }

    // Submit a flow and wait for the result via the start+poll interface
    // a bit dirty?
    def evaluateTestStartAndPoll(
      id: String,
      waitBeforePoll: Duration
    ): ZIO[DurableLog with KeyValueStore, Exception, Option[IO[DynamicValue, FlowResult]]] =
      ZIO.scoped {
        val fId = FlowId.unsafeMake(id)
        MockExecutors.persistent().flatMap { executor =>
          executor.restartAll().orDie *>
            executor.start(fId, zflow) *>
            ZIO.sleep(waitBeforePoll) *>
            executor.pollWorkflowDynTyped(fId)
        }
      }
  }
}
