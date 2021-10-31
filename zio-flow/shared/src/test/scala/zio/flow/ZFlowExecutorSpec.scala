package zio.flow

import zio.flow.remote._
import java.net.URI
import java.time.Instant
import zio.flow.zFlow.ZFlowExecutor.InMemory.{CompileStatus, State, TState}
import zio.flow.remote.Remote
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow.utils.ZFlowAssertionSyntax.Mocks.mockInMemoryTestClock
import zio.flow.zFlow.ZFlow
import zio.schema.DeriveSchema.gen
import zio.schema.Schema
import zio.test.Assertion.{dies, equalTo, fails, hasMessage}
import zio.test.TestAspect.ignore
import zio.test._
import zio.{Promise, Ref}

object ZFlowExecutorSpec extends DefaultRunnableSpec {

  implicit val nothingSchema: Schema[Nothing] = Schema.fail("Nothing does not need to be serialised.")

  implicit def variableSchema[A]: Schema[Variable[A]]      = Schema.fail("Variable schema")
  implicit val acitivityErrorSchema: Schema[ActivityError] = Schema.fail("Activity Error schema")

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

  val suite1: Spec[Environment, TestFailure[Any], TestSuccess] = suite("Basic test for all  ZFlow types")(
    testM("Test Return") {
      val compileResult = (ZFlow.Return(12): ZFlow[Any, ActivityError, Int]).evaluateTestInMem
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Now") {
      val compileResult = ZFlow.now.evaluateTestInMem(implicitly[Schema[Instant]], nothingSchema)
      assertM(compileResult)(equalTo(Instant.now()))
    } @@ ignore,
    testM("Test Unwrap") {
      val compileResult = ZFlow
        .Unwrap[Any, Nothing, Int](Remote(ZFlow.succeed(12)))
        .evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test ForEach") {
      val compileResult = ZFlow
        .Foreach[Any, Nothing, Int, Int](Remote(List(10, 20, 30)), (a: Remote[Int]) => a + 5)
        .evaluateTestInMem(implicitly[Schema[List[Int]]], nothingSchema)
      assertM(compileResult)(equalTo(List(15, 25, 35)))
    } @@ ignore,
    testM("Test Ensuring") {
      val compileResult = ZFlow
        .Ensuring(ZFlow.succeed(12), ZFlow.input[String])
        .provide("Some input")
        .evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Transaction") {
      val compileResult = ZFlow
        .Transaction[Any, Nothing, Int](ZFlow.succeed(12))
        .evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Input and Provide") {
      val compileResult =
        ZFlow.input[String].provide("Some input").evaluateTestInMem(implicitly[Schema[String]], nothingSchema)
      assertM(compileResult)(equalTo("Some input"))
    },
    testM("Test Fork") {
      def executingFlowSchema[E, A]: Schema[ExecutingFlow[E, A]] = Schema.fail("Executing flow schema")

      ZFlow
        .Fork[Any, Nothing, Int](ZFlow.succeed(12))
        .evaluateTestInMem(executingFlowSchema, nothingSchema)
        .map(r => assert(r.isInstanceOf[ExecutingFlow[Nothing, Int]])(equalTo(true)))
    },
    testM("Test Die")(
      assertM(ZFlow.Die.evaluateTestInMem(nothingSchema, nothingSchema).run)(
        dies(hasMessage(equalTo("Could not evaluate ZFlow")))
      )
    ),
    testM("Test RunActivity") {
      val compileResult = ZFlow.RunActivity(12, testActivity).evaluateTestInMem
      assertM(compileResult)(equalTo(12))
    },
    testM("Test NewVar")(
      ZFlow
        .NewVar("var1", Remote(20))
        .evaluateTestInMem(variableSchema[Int], nothingSchema)
        .map(r => assert(r.isInstanceOf[Variable[Int]])(equalTo(true)))
    ),
    testM("Retry Until - No Transaction") {
      assertM(ZFlow.RetryUntil.evaluateTestInMem(nothingSchema, nothingSchema).run)(
        dies(hasMessage(equalTo("There is no transaction to retry.")))
      )
    },
    testM("Test OrTry - right succeeds") {
      val compileResult = ZFlow
        .OrTry[Any, Nothing, Int](ZFlow.transaction(_ => ZFlow.RetryUntil), ZFlow.succeed(12))
        .evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test OrTry - left succeeds") {
      val compileResult = ZFlow
        .OrTry[Any, Nothing, Int](ZFlow.succeed(12), ZFlow.transaction(_ => ZFlow.RetryUntil))
        .evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Fold") {
      checkM(Gen.anyString, Gen.anyInt) { case (s, v) =>
        val compileResult1 =
          ZFlow.succeed(s).foldM(ifError, ifSuccess).evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult1)(equalTo(s.length))
        val compileResult2 =
          ZFlow.fail(v).foldM(ifError, ifSuccess).evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult2)(equalTo(v + 5))
      }
    },
    testM("Test Fail") {
      implicit val schema: Schema[IllegalStateException] =
        Schema.primitive[String].transform(s => new IllegalStateException(s), exp => exp.getMessage)
      assertM(ZFlow.Fail(Remote(new IllegalStateException("Illegal"))).evaluateTestInMem(nothingSchema, schema).run)(
        fails(hasMessage(equalTo("Illegal")))
      )
    },
    testM("Test Modify : Int => Bool") {
      checkM(Gen.anyInt) { case v =>
        val compileResult = (for {
          variable         <- ZFlow.newVar[Int]("variable1", v)
          modifiedVariable <- variable.modify(isOdd)
        } yield modifiedVariable).evaluateTestInMem(implicitly[Schema[Boolean]], nothingSchema)
        assertM(compileResult)(equalTo(false))
      }
    },
    testM("Test Modify : String => Int") {
      checkM(Gen.anyString) { case s =>
        val compileResult = (for {
          variable         <- ZFlow.newVar[String]("variable1", s)
          modifiedVariable <- variable.modify(length)
        } yield modifiedVariable).evaluateTestInMem(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult)(equalTo(s.length))
      }
    },
    testM("Test Iterate") {
      val compileResult = ZFlow
        .Iterate[Any, ActivityError, Int](
          ZFlow.RunActivity[Any, Int](12, testActivity),
          (r: Remote[Int]) => ZFlow.succeed(r + 1),
          (r: Remote[Int]) => r === Remote(15)
        )
        .evaluateTestInMem(implicitly[Schema[Int]], implicitly[Schema[ActivityError]])
      assertM(compileResult)(equalTo(12))
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

  //  val suite3: Spec[Environment, TestFailure[ActivityError], TestSuccess] =
  //    suite("Test Interrupt")(testM("Test Interrupt") {
  //      implicit val exFloSchema: Schema[ExecutingFlow[Nothing, Int]] = Schema.fail("exFlow schema")
  //      val result                                                    = for {
  //        inMemory       <- mockInMemoryTestClock
  //        exFlow         <- inMemory.submit("1234", ZFlow.Fork[Any, Nothing, Int](ZFlow.succeed(12)))
  //        compiledResult <- inMemory.submit("1234", ZFlow.Interrupt[Nothing, Int](Remote(exFlow)))
  //      } yield compiledResult
  //      assertM(result)(equalTo(Done))
  //    })

  override def spec: ZSpec[Environment, Failure] = suite("Test ZFlow combinators")(
    suite1,
    suite2
  )
}
