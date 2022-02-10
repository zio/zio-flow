package zio.flow.utils

import zio._
import zio.flow.ZFlow
import zio.flow.internal.{DurableLog, KeyValueStore}
import zio.flow.utils.MocksForGCExample.mockInMemoryForGCExample
import zio.schema.Schema

object ZFlowAssertionSyntax {

  import zio.flow.utils.MockExecutors._

  implicit final class InMemoryZFlowAssertion[E, A](private val zflow: ZFlow[Any, E, A]) {

    def evaluateTestInMem(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryTestClock
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateLiveInMem(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryLiveClock
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateInMemForGCExample(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Any, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryForGCExample
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateLivePersistent(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[DurableLog, E, A] =
      for {
        persistentEval <- mockPersistentLiveClock
        result         <- persistentEval.submit("1234", zflow)
      } yield result

    def evaluateTestPersistent(id: String)(implicit
      schemaA: Schema[A],
      schemaE: Schema[E]
    ): ZIO[Clock with DurableLog with KeyValueStore, E, A] =
      mockPersistentTestClock.use { executor =>
        executor.submit(id.toString, zflow)
      }
  }
}
