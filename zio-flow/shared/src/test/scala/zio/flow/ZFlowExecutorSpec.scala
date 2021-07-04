package zio.flow

import java.net.URI
import java.time.Instant

import zio.Schedule.Decision.Done
import zio.flow.ZFlowExecutor.InMemory.{ CompileStatus, State, TState }
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow.utils.ZFlowAssertionSyntax.Mocks.mockInMemoryTestClock
import zio.schema.Schema
import zio.test.Assertion.{ dies, equalTo, fails, hasMessage }
import zio.test.TestAspect.ignore
import zio.test._
import zio.{ Promise, Ref }

object ZFlowExecutorSpec extends DefaultRunnableSpec {
  val ifError: Remote[Int] => ZFlow[Any, Nothing, Int]      = r => ZFlow.succeed(r + 5)
  val ifSuccess: Remote[String] => ZFlow[Any, Nothing, Int] = r => ZFlow.succeed(r.length)

  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  def length(a: Remote[String]): (Remote[Int], Remote[String]) = (a.length, a)

  val testActivity: Activity[Any, Int] =
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

  val suite1: Spec[Environment, TestFailure[Any], TestSuccess] = suite("Test Unwrap")(
    testM("Test Return")(ZFlow.Return(12) <=> 12),
    testM("Test Now")(ZFlow.now <=> Instant.now()) @@ ignore,
    testM("Test Unwrap")(ZFlow.Unwrap[Any, Nothing, Int](Remote(ZFlow.succeed(12))) <=> 12),
    testM("Test ForEach")(
      ZFlow.Foreach[Any, Nothing, Int, Int](Remote(List(10, 20, 30)), (a: Remote[Int]) => a + 5) <=> List(15, 25, 35)
    ),
    testM("Test Ensuring")(
      ZFlow.Ensuring(ZFlow.succeed(12), ZFlow.input[String]).provide("Some input") <=> 12
    ),
    testM("Test Transaction")(
      ZFlow.Transaction[Any, Nothing, Int](ZFlow.succeed(12)) <=> 12
    ),
    testM("Test Input and Provide")(ZFlow.input[String].provide("Some input") <=> "Some input"),
    testM("Test Fork")(
      ZFlow
        .Fork[Any, Nothing, Int](ZFlow.succeed(12))
        .evaluateTestInMem
        .map(r => assert(r.isInstanceOf[ExecutingFlow[Nothing, Int]])(equalTo(true)))
    ),
    testM("Test Die")(
      assertM(ZFlow.Die.evaluateTestInMem.run)(dies(hasMessage(equalTo("Could not evaluate ZFlow"))))
    ),
    testM("Test RunActivity")(
      ZFlow.RunActivity(12, testActivity) <=> 12
    ),
    testM("Test NewVar")(
      ZFlow.NewVar("var1", Remote(20)).evaluateTestInMem.map(r => assert(r.isInstanceOf[Variable[Int]])(equalTo(true)))
    ),
    testM("Retry Until - No Transaction")(
      assertM(ZFlow.RetryUntil.evaluateTestInMem.run)(dies(hasMessage(equalTo("There is no transaction to retry."))))
    ),
    testM("Test OrTry - right succeeds")(
      ZFlow.OrTry[Any, Nothing, Int](ZFlow.transaction(_ => ZFlow.RetryUntil), ZFlow.succeed(12)) <=> 12
    ),
    testM("Test OrTry - left succeeds")(
      ZFlow.OrTry[Any, Nothing, Int](ZFlow.succeed(12), ZFlow.transaction(_ => ZFlow.RetryUntil)) <=> 12
    ),
    testM("Test Fold") {
      checkM(Gen.anyString, Gen.anyInt) { case (s, v) =>
        ZFlow.succeed(s).foldM(ifError, ifSuccess) <=> s.length
        ZFlow.fail(v).foldM(ifError, ifSuccess) <=> v + 5
      }
    },
    testM("Test Fail") {
      implicit val schema: Schema[IllegalStateException] =
        Schema.primitive[String].transform(s => new IllegalStateException(s), exp => exp.getMessage)
      assertM(ZFlow.Fail(Remote(new IllegalStateException("Illegal"))).evaluateTestInMem.run)(
        fails(hasMessage(equalTo("Illegal")))
      )
    },
    testM("Test Modify : Int => Bool") {
      checkM(Gen.anyInt) { case v =>
        (for {
          variable         <- ZFlow.newVar[Int]("variable1", v)
          modifiedVariable <- variable.modify(isOdd)
        } yield modifiedVariable) <=> false
      }
    },
    testM("Test Modify : String => Int") {
      checkM(Gen.anyString) { case s =>
        (for {
          variable         <- ZFlow.newVar[String]("variable1", s)
          modifiedVariable <- variable.modify(length)
        } yield modifiedVariable) <=> s.length
      }
    },
    //TODO : Possible Bug
    testM("Test Iterate") {
      ZFlow.Iterate[Any, ActivityError, Int](
        ZFlow.RunActivity[Any, Int](12, testActivity),
        (r: Remote[Int]) => ZFlow.succeed(r + 1),
        (r: Remote[Int]) => r === Remote(15)
      ) <=> 12
    }
  )

  val suite2: Spec[Environment, TestFailure[Nothing], TestSuccess] =
    suite("Test RetryUntil")(testM("Test compile status of RetryUntil") {
      val result = for {
        inMemory      <- mockInMemoryTestClock
        promise       <- Promise.make[Nothing, String]
        ref           <- Ref.make[State](State(TState.Empty, Map.empty[String, Ref[_]]))
        compileResult <-
          inMemory.compile[Any, Nothing, String](promise, ref, 12, ZFlow.transaction(_ => ZFlow.RetryUntil))
      } yield compileResult

      assertM(result)(equalTo(CompileStatus.Suspended))
    })

  val suite3: Spec[Environment, TestFailure[ActivityError], TestSuccess] =
    suite("Test Interrupt")(testM("Test Interrupt") {
      implicit val exFloSchema: Schema[ExecutingFlow[Nothing, Int]] = Schema.fail("exFlow schema")
      val result                                                    = for {
        inMemory       <- mockInMemoryTestClock
        exFlow         <- inMemory.submit("1234", ZFlow.Fork[Any, Nothing, Int](ZFlow.succeed(12)))
        compiledResult <- inMemory.submit("1234", ZFlow.Interrupt[Nothing, Int](Remote(exFlow)))
      } yield compiledResult
      assertM(result)(equalTo(Done))
    })

  override def spec: ZSpec[Environment, Failure] = suite("Test ZFlow combinators")(
    suite1,
    suite2
  )
}
