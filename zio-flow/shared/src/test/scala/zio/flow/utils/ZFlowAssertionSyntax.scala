package zio.flow.utils

import zio._
import zio.flow.internal.{DurableLog, KeyValueStore}
import zio.flow.{FlowId, ZFlow}
import zio.schema.Schema

object ZFlowAssertionSyntax {

  import zio.flow.utils.MockExecutors._

  implicit final class InMemoryZFlowAssertion[E, A](private val zflow: ZFlow[Any, E, A]) {
    def evaluateTestPersistent(id: String)(implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[Console with Clock with DurableLog with KeyValueStore, E, A] =
      ZIO.scoped {
        mockPersistentTestClock.flatMap { executor =>
          executor.restartAll().orDie *>
            executor.submit(FlowId(id), zflow)
        }
      }
  }
}
