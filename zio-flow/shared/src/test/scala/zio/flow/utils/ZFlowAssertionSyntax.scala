package zio.flow.utils

import java.net.URI

import zio.clock.Clock
import zio.console.Console
import zio.flow.ZFlowExecutor.InMemory
import zio.flow.{ Activity, ActivityError, Operation, OperationExecutor, ZFlow }
import zio.schema.Schema
import zio.test.Assertion.equalTo
import zio.test.assertM
import zio.{ Has, Ref, ZIO, console }

object ZFlowAssertionSyntax {

  object Mocks {
    val mockActivity: Activity[Any, Int] =
      Activity(
        "Test Activity",
        "Mock activity created for test",
        Operation.Http[Any, Int](
          new URI("testUrlForActivity.com"),
          "GET",
          Map.empty[String, String],
          Schema.fail("No schema"),
          implicitly[Schema[Int]]
        ),
        ZFlow.succeed(12),
        ZFlow.succeed(15)
      )

    object mockOpExec extends OperationExecutor[Console with Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
        console.putStrLn("Activity processing") *> ZIO.succeed(input.asInstanceOf[A])
    }

    val mockInMemoryTestClock: ZIO[Clock with Console, Nothing, InMemory[String, Clock with Console]] = ZIO
      .environment[Clock with Console]
      .flatMap(testClock =>
        Ref
          .make[Map[String, Ref[InMemory.State]]](Map.empty)
          .map(ref => InMemory[String, Clock with Console](testClock, mockOpExec, ref))
      )

    val mockInMemoryLiveClock: ZIO[Any, Nothing, InMemory[String, Has[Clock.Service] with Has[Console.Service]]] =
      Ref
        .make[Map[String, Ref[InMemory.State]]](Map.empty)
        .map(ref =>
          InMemory(Has(zio.clock.Clock.Service.live) ++ Has(zio.console.Console.Service.live), mockOpExec, ref)
        )
  }

  import Mocks._
  implicit final class InMemoryZFlowAssertion[R, E, A](private val zflow: ZFlow[Any, E, A]) {
    def <=>(that: A) = {
      val compileResult = evaluateTestInMem
      assertM(compileResult)(equalTo(that))
    }

    def evaluateTestInMem: ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryTestClock
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateLiveInMem: ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryLiveClock
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }
  }

}
