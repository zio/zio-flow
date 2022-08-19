//package zio.flow
//
//import java.net.URI
//import java.time.Instant
//import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
//import zio.flow.internal.ZFlowExecutor.InMemory.{CompileStatus, State, TState}
//import zio.flow.utils.MockExecutors.mockInMemoryTestClock
//import zio.schema.DeriveSchema.gen
//import zio.schema.Schema
//import zio.test.Assertion.{anything, dies, equalTo, fails, hasMessage}
//import zio.test.TestAspect.ignore
//import zio.test._
//import zio.{Promise, Ref}
//
//object ZFlowExecutorSpec extends ZIOSpecDefault {
//
//  val ifError: Remote[Int] => ZFlow[Any, Nothing, Int]      = r => ZFlow.succeed(r + 5)
//  val ifSuccess: Remote[String] => ZFlow[Any, Nothing, Int] = r => ZFlow.succeed(r.length)
//
//  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
//    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)
//
//  def length(a: Remote[String]): (Remote[Int], Remote[String]) = (a.length, a)
//
//  val testActivity: Activity[Any, Int] =
//    Activity(
//      "Test Activity",
//      "Mock activity created for test",
//      Operation.Http[Any, Int](
//        new URI("testUrlForActivity.com"),
//        "GET",
//        Map.empty[String, String],
//        Schema.fail("No schema"),
//        implicitly[Schema[Int]]
//      ),
//      ZFlow.succeed(12),
//      ZFlow.succeed(15)
//    )
//
//  val suite1: Spec[Environment, TestFailure[Any], TestSuccess] = suite("Basic test for all  ZFlow types")(
//    test("Test Return") {
//      val compileResult = (ZFlow.Return(12): ZFlow[Any, ActivityError, Int]).evaluateTestInMem
//      assertM(compileResult)(equalTo(12))
//    },
//    test("Test Now") {
//      val compileResult = ZFlow.now.evaluateTestInMem
//      assertM(compileResult.map(i => i.getEpochSecond))(equalTo(Instant.now().getEpochSecond))
//    } @@ ignore,
//    test("Test Unwrap") {
//      val compileResult = ZFlow
//        .Unwrap[Any, Nothing, Int](Remote(ZFlow.succeed(12)))
//        .evaluateTestInMem
//      assertM(compileResult)(equalTo(12))
//    },
//    test("Test ForEach") {
//      val compileResult = ZFlow
//        .foreach(Remote(List(10, 20, 30)))(_ + 5)
//        .evaluateTestInMem
//      assertM(compileResult)(equalTo(List(15, 25, 35)))
//    } @@ ignore,
//    test("Test Ensuring") {
//      val compileResult = ZFlow
//        .succeed(12)
//        .ensuring(ZFlow.input[String])
//        .provide("Some input")
//        .evaluateTestInMem
//      assertM(compileResult)(equalTo(12))
//    },
//    test("Test Transaction") {
//      val compileResult = ZFlow
//        .Transaction[Any, Nothing, Int](ZFlow.succeed(12))
//        .evaluateTestInMem
//      assertM(compileResult)(equalTo(12))
//    },
//    test("Test Input and Provide") {
//      val compileResult =
//        ZFlow.input[String].provide("Some input").evaluateTestInMem
//      assertM(compileResult)(equalTo("Some input"))
//    },
//    test("Test Fork") {
//      def executingFlowSchema[E, A]: Schema[ExecutingFlow[E, A]] = Schema.fail("Executing flow schema")
//
//      ZFlow
//        .Fork[Any, Nothing, Int](ZFlow.succeed(12))
//        .evaluateTestInMem
//        .map(r => assert(r.isInstanceOf[ExecutingFlow[Nothing, Int]])(equalTo(true)))
//    },
//    test("Test Die")(
//      assertM(ZFlow.Die.evaluateTestInMem.exit)(
//        dies(hasMessage(equalTo("Could not evaluate ZFlow")))
//      )
//    ),
//    test("Test RunActivity") {
//      val compileResult = ZFlow.RunActivity(12, testActivity).evaluateTestInMem
//      assertM(compileResult)(equalTo(12))
//    },
//    test("Test NewVar")(
//      ZFlow
//        .NewVar("var1", Remote(20))
//        .evaluateTestInMem
//        .map(r => assert(r.isInstanceOf[Remote.Variable[Int]])(equalTo(true)))
//    ),
//    test("Retry Until - No Transaction") {
//      assertM(ZFlow.RetryUntil.evaluateTestInMem.exit)(
//        dies(hasMessage(equalTo("There is no transaction to retry.")))
//      )
//    },
//    test("Test OrTry - right succeeds") {
//      val compileResult = ZFlow
//        .OrTry[Any, Nothing, Int](ZFlow.transaction(_ => ZFlow.RetryUntil), ZFlow.succeed(12))
//        .evaluateTestInMem
//      assertM(compileResult)(equalTo(12))
//    },
//    test("Test OrTry - left succeeds") {
//      val compileResult = ZFlow
//        .OrTry[Any, Nothing, Int](ZFlow.succeed(12), ZFlow.transaction(_ => ZFlow.RetryUntil))
//        .evaluateTestInMem
//      assertM(compileResult)(equalTo(12))
//    },
//    test("Test Fold") {
//      check(Gen.string, Gen.int) { case (s, v) =>
//        val compileResult1 =
//          ZFlow.succeed(s).foldM(ifError, ifSuccess).evaluateTestInMem
//        assertM(compileResult1)(equalTo(s.length))
//        val compileResult2 =
//          ZFlow.fail(v).foldM(ifError, ifSuccess).evaluateTestInMem
//        assertM(compileResult2)(equalTo(v + 5))
//      }
//    },
//    test("Test Fail") {
//      implicit val schema: Schema[IllegalStateException] =
//        Schema.primitive[String].transform(s => new IllegalStateException(s), exp => exp.getMessage)
//      assertM(ZFlow.Fail(Remote(new IllegalStateException("Illegal"))).evaluateTestInMem.exit)(
//        fails(hasMessage(equalTo("Illegal")))
//      )
//    },
//    test("Test Modify : Int => Bool") {
//      check(Gen.int) { case v =>
//        val compileResult = (for {
//          variable         <- ZFlow.newVar[Int]("variable1", v)
//          modifiedVariable <- variable.modify(isOdd)
//        } yield modifiedVariable).evaluateTestInMem
//        assertM(compileResult)(equalTo(false))
//      }
//    },
//    test("Test Modify : String => Int") {
//      check(Gen.string) { case s =>
//        val compileResult = (for {
//          variable         <- ZFlow.newVar[String]("variable1", s)
//          modifiedVariable <- variable.modify(length)
//        } yield modifiedVariable).evaluateTestInMem
//        assertM(compileResult)(equalTo(s.length))
//      }
//    },
//    test("Test Iterate") {
//      val compileResult: ZFlow[Any, Nothing, Int] = ZFlow.Iterate[Any, Nothing, Int](
//        Remote(12),
//        (r: Remote[Int]) => Remote[ZFlow[Any, Nothing, Int]](ZFlow.succeed(r + 1)),
//        (r: Remote[Int]) => r === Remote(15)
//      )
//      assertM(compileResult.evaluateTestInMem)(equalTo(12))
//    }
//  )
//
//  val suite2: Spec[Environment, TestFailure[Nothing], TestSuccess] =
//    suite("Test RetryUntil")(test("Test compile status of RetryUntil") {
//      val result = for {
//        inMemory <- mockInMemoryTestClock
//        promise  <- Promise.make[Nothing, String]
//        ref      <- Ref.make[State](State(TState.Empty, Set.empty[String]))
//        compileResult <-
//          inMemory
//            .compile[Any, Nothing, String](promise, ref, 12, ZFlow.transaction(_ => ZFlow.RetryUntil))
//            .provideCustom(RemoteContext.inMemory)
//      } yield compileResult
//
//      assertM(result)(equalTo(CompileStatus.Suspended))
//    })
//
//  //  val suite3: Spec[Environment, TestFailure[ActivityError], TestSuccess] =
//  //    suite("Test Interrupt")(testM("Test Interrupt") {
//  //      implicit val exFloSchema: Schema[ExecutingFlow[Nothing, Int]] = Schema.fail("exFlow schema")
//  //      val result                                                    = for {
//  //        inMemory       <- mockInMemoryTestClock
//  //        exFlow         <- inMemory.submit("1234", ZFlow.Fork[Any, Nothing, Int](ZFlow.succeed(12)))
//  //        compiledResult <- inMemory.submit("1234", ZFlow.Interrupt[Nothing, Int](Remote(exFlow)))
//  //      } yield compiledResult
//  //      assertM(result)(equalTo(Done))
//  //    })
//
//  override def spec = suite("Test ZFlow combinators")(
//    suite1,
//    suite2
//  )
//}
