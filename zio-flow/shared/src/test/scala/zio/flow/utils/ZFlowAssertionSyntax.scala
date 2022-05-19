package zio.flow.utils

import zio._
import zio.flow.internal.{DurableLog, KeyValueStore}
import zio.flow.mock.MockedOperation
import zio.flow.{FlowId, ZFlow}
import zio.schema.Schema

object ZFlowAssertionSyntax {

  implicit final class InMemoryZFlowAssertion[E, A](private val zflow: ZFlow[Any, E, A]) {
    def evaluateTestPersistent(id: String, mock: MockedOperation = MockedOperation.Empty)(implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[Console with Clock with DurableLog with KeyValueStore, E, A] =
      ZIO.scoped {
        MockExecutors.persistent(mock).flatMap { executor =>
          executor.restartAll().orDie *>
            executor.submit(FlowId(id), zflow)
        }
      }
  }
}
