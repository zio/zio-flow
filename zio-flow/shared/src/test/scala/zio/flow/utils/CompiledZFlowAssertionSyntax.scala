package zio.flow.utils

import zio.clock.Clock
import zio.console.Console
import zio.flow.ZFlowExecutor.InMemory
import zio.flow.{ ActivityError, Operation, OperationExecutor, ZFlow }
import zio.test.Assertion.equalTo
import zio.test.assertM
import zio.{ Ref, ZIO, console }

object CompiledZFlowAssertionSyntax {

  object mockOpExec extends OperationExecutor[Console with Clock] {
    override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
      console.putStrLn("Activity processing") *> ZIO.succeed(input.asInstanceOf[A])
  }

  val mockInMemory: ZIO[Clock with Console, Nothing, InMemory[String, Clock with Console]] = ZIO
    .environment[Clock with Console]
    .flatMap(testClock =>
      Ref
        .make[Map[String, Ref[InMemory.State]]](Map.empty)
        .map(ref => InMemory[String, Clock with Console](testClock, mockOpExec, ref))
    )

  implicit final class InMemoryZFlowAssertion[R, E, A](private val zflow: ZFlow[Any, E, A]) {
    def <=>(that: A) = {
      val compileResult = evaluateInMem
      assertM(compileResult)(equalTo(that))
    }

    def evaluateInMem: ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemory
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }
  }

}
