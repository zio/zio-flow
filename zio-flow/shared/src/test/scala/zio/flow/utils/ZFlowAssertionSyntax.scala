package zio.flow.utils

import zio._
import zio.flow.internal.{DurableLog, KeyValueStore}
import zio.flow.{FlowId, ZFlow}
import zio.schema.{DynamicValue, Schema}

object ZFlowAssertionSyntax {

  import zio.flow.utils.MockExecutors._

  implicit final class InMemoryZFlowAssertion[E, A](private val zflow: ZFlow[Any, E, A]) {
    def evaluateTestPersistent(id: String)(implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[DurableLog with KeyValueStore, E, A] =
      ZIO.scoped {
        mockPersistentTestClock.flatMap { executor =>
          executor.restartAll().orDie *>
            executor.submit(FlowId(id), zflow)
        }
      }

    // Submit a flow and wait for the result via the start+poll interface
    // a bit dirty?
    def evaluateTestStartAndPoll(
      id: String,
      waitBeforePoll: Duration
    ): ZIO[DurableLog with KeyValueStore, Exception, Option[IO[DynamicValue, DynamicValue]]] =
      ZIO.scoped {
        val fId = FlowId(id)
        mockPersistentTestClock.flatMap { executor =>
          executor.restartAll().orDie *>
            executor.start(fId, zflow) *>
            ZIO.sleep(waitBeforePoll) *>
            executor.pollWorkflowDynTyped(fId)
        }
      }
  }
}
