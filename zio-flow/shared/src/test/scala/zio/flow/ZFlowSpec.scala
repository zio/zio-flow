package zio.flow

import zio.{Ref, ZIO}
import zio.clock.Clock
import zio.flow.ZFlowExecutor.InMemory
import zio.test.Assertion.equalTo
import zio.test.TestAspect.ignore
import zio.test.{DefaultRunnableSpec, ZSpec, assertM}

import java.time.Instant
import java.util.concurrent.TimeUnit

object ZFlowSpec extends DefaultRunnableSpec {

  object mockOpExec extends OperationExecutor[Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Clock, ActivityError, A] = ZIO.succeed(().asInstanceOf[A])
    }
    val mockInMemory = ZIO.environment[Clock]
      .flatMap(testClock => Ref.make[Map[String, Ref[InMemory.State]]](Map.empty).map(ref => InMemory[String, Clock](testClock, mockOpExec, ref)))

    val suite1 = suite("Test ZFlow Combinators")(
      testM("Test Return") {
      val returnVal = ZFlow.succeed(Remote(12))
      val compileResult = for {
        inMemory <- mockInMemory
        result <- inMemory.submit("1234", returnVal)
      } yield result
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Now"){
      val now = ZFlow.now
      val compileResult: ZIO[Clock, Nothing, Instant] = for{
        inMemory <- mockInMemory
        result <- inMemory.submit("1234", now)
      } yield result
      assertM(ZIO.environment[Clock].flatMap(c => c.get.currentTime(TimeUnit.SECONDS)))(equalTo(0L))
    } @@ ignore,
      testM("Test Modify"){
        val var1: ZFlow[Any, Nothing, Variable[Int]] = ZFlow.newVar[Int]("variable1", 10)
        val modified: ZFlow[Any, Nothing, Int] = for {
          variable <- var1
          modifiedVariable <- variable.modify(v => (v + 2, v))
        } yield modifiedVariable
        val compileResult = for{
          inMemory <- mockInMemory
          result <- inMemory.submit("1234", modified)
        } yield result

        assertM(compileResult)(equalTo(12))
      },
      testM("More test on Modify"){
        def isOdd(a : Remote[Int]) : Remote[Boolean] = {

        }
        val intVar
      })

  override def spec = suite("ZFlowSpec")(suite1)
}

