package zio.flow.internal

import zio.{ZNothing, _}
import zio.flow._
import zio.flow.mock.MockedOperation
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.{DeriveSchema, Schema}
import zio.test.Assertion.{dies, equalTo, fails, hasMessage, isNone}
import zio.test.{Live, TestAspect, TestClock, TestResult, assert, assertTrue}

import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object PersistentExecutorSpec extends ZIOFlowBaseSpec {

  private val unit: Unit = ()
  private val counter    = new AtomicInteger(0)

  private val testActivity: Activity[Int, Int] =
    Activity(
      "Test Activity",
      "Mock activity created for test",
      Operation.Http[Int, Int](
        new URI("testUrlForActivity.com"),
        "GET",
        Map.empty[String, String],
        Schema[Int],
        Schema[Int]
      ),
      ZFlow.succeed(12),
      ZFlow.unit
    )

  val suite1 = suite("Operators in single run")(
    testFlow("succeed")(ZFlow.succeed(12)) { result =>
      assertTrue(result == 12)
    },
    testFlow("newVar") {
      for {
        variable         <- ZFlow.newVar("variable1", 10)
        modifiedVariable <- variable.modify(isOdd)
        v                <- modifiedVariable
      } yield v
    } { result =>
      assertTrue(result == false)

    },
    testFlow("foldM - success side") {
      ZFlow
        .succeed(15)
        .foldFlow(_ => ZFlow.unit, _ => ZFlow.unit)
    } { result =>
      assertTrue(result == unit)
    },
    testFlow("foldM - error side") {
      ZFlow
        .fail(15)
        .foldFlow(_ => ZFlow.unit, _ => ZFlow.unit)
    } { result =>
      assertTrue(result == unit)
    },
    testFlow("flatMap 1") {
      ZFlow
        .succeed(12)
        .flatMap(rA => rA + Remote(1))
    } { result =>
      assertTrue(result == 13)
    },
    testFlow("flatMap 2") {
      for {
        a <- ZFlow.succeed(12)
        b <- ZFlow.succeed(a + 10)
      } yield b
    } { result =>
      assertTrue(result == 22)
    },
    testFlow("input") {
      ZFlow.input[Int].provide(12)
    } { result =>
      assertTrue(result == 12)
    },
    testFlow("ensuring") {
      for {
        res <- ZFlow.newVar[Int]("res", 0)
        _   <- res.set(100)
        a   <- ZFlow.succeed(12).ensuring(res.set(15))
        b   <- res.get
      } yield a + b
    } { result =>
      assertTrue(result == 27)
    },
    testFlow("provide") {
      {
        for {
          a <- ZFlow.succeed(15)
          b <- ZFlow.input[Int]
        } yield a + b
      }.provide(11)
    } { result =>
      assertTrue(result == 26)
    },
    test("now") {
      TestClock.adjust(5.seconds) *> {
        val flow = ZFlow.now
        flow.evaluateTestPersistent("now").map { result =>
          assertTrue(result.getEpochSecond == 5L)
        }
      }
    },
    test("waitTill") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = for {
                 _   <- ZFlow.waitTill(Remote(Instant.ofEpochSecond(curr + 2L)))
                 now <- ZFlow.now
               } yield now
        fiber  <- flow.evaluateTestPersistent("waitTill").fork
        _      <- TestClock.adjust(2.seconds)
        result <- fiber.join
      } yield assertTrue(result.getEpochSecond == 2L)
    },
    test("sleep") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = for {
                 _   <- ZFlow.sleep(2.seconds)
                 now <- ZFlow.now
               } yield now
        fiber  <- flow.evaluateTestPersistent("sleep").fork
        _      <- TestClock.adjust(2.seconds)
        result <- fiber.join
      } yield assertTrue(result.getEpochSecond == 2L)
    },
    testFlow("Activity") {
      testActivity(12)
    }(
      assert = result => assertTrue(result == 12),
      mock = MockedOperation.Http[Int, Int](
        urlMatcher = equalTo(new URI("testUrlForActivity.com")),
        methodMatcher = equalTo("GET"),
        result = () => 12
      )
    ),
    testFlow("iterate") {
      ZFlow.succeed(1).iterate[Any, ZNothing, Int](_ + 1)(_ !== 10)
    } { result =>
      assertTrue(result == 10)
    } @@ TestAspect.ignore, // TODO: fix recursion support
    testFlow("Read") {
      for {
        variable <- ZFlow.newVar[Int]("var", 0)
        a        <- variable.get
      } yield a
    } { result =>
      assertTrue(result == 0)
    },
    testFlow("Modify") {
      for {
        variable <- ZFlow.newVar[Int]("var", 0)
        a        <- variable.modify(n => (n - 1, n + 1))
        b        <- variable.get
      } yield (a, b)
    } { result =>
      val expected = (-1, 1)
      assertTrue(result == expected)
    },
    testFlowAndLogs("log") {
      ZFlow.log("first message") *> ZFlow.log("second message").as(100)
    } { (result, logs) =>
      val i1 = logs.indexOf("first message")
      val i2 = logs.indexOf("second message")
      assertTrue(
        result == 100,
        i1 >= 0,
        i2 >= 0,
        i1 < i2
      )
    },
    suite("transactions")(
      testFlow("nop transaction") {
        ZFlow.transaction { _ =>
          ZFlow.succeed(100)
        }
      } { result =>
        assertTrue(result == 100)
      },
      testFlow("setting variables in transaction") {
        for {
          var1 <- ZFlow.newVar[Int]("var1", 10)
          var2 <- ZFlow.newVar[Int]("var2", 20)
          _ <- ZFlow.transaction { _ =>
                 for {
                   _ <- var1.set(100)
                   _ <- var2.set(200)
                 } yield ()
               }
          v1 <- var1.get
          v2 <- var2.get
        } yield (v1, v2)
      } { result =>
        assertTrue(result == ((100, 200)))
      },
      testFlow("setting variables in a forked transaction") {
        for {
          var1 <- ZFlow.newVar[Int]("var1", 10)
          fiber <- ZFlow.transaction { _ =>
                     for {
                       _ <- var1.set(100)
                     } yield ()
                   }.fork
          _  <- fiber.await
          v1 <- var1.get
        } yield v1
      } { result =>
        assertTrue(result == 100)
      },
      testFlow("can return with a variable not existing outside") {
        for {
          fiber <- ZFlow.transaction { _ =>
                     for {
                       variable <- ZFlow.newVar("inner", 123)
                       v0       <- variable.get
                     } yield v0
                   }.fork
          v1 <- fiber.await
        } yield v1
      } { result =>
        assertTrue(result == Right(123))
      },
      testFlow("conflicting change of shared variable in transaction", periodicAdjustClock = Some(500.millis)) {
        for {
          var1 <- ZFlow.newVar[Int]("var1", 10)
          var2 <- ZFlow.newVar[Int]("var2", 20)
          now  <- ZFlow.now
          fib1 <- ZFlow.transaction { _ =>
                    for {
                      _ <- ZFlow.waitTill(now.plusSeconds(1L))
                      _ <- var1.update(_ + 1)
                      _ <- ZFlow.waitTill(now.plusSeconds(1L))
                      _ <- var2.update(_ + 1)
                      _ <- ZFlow.waitTill(now.plusSeconds(1L))
                    } yield ()
                  }.fork
          fib2 <- ZFlow.transaction { _ =>
                    for {
                      _ <- ZFlow.waitTill(now.plusSeconds(1L))
                      _ <- var1.update(_ + 1)
                      _ <- ZFlow.waitTill(now.plusSeconds(1L))
                      _ <- var2.update(_ + 1)
                    } yield ()
                  }.fork

          _  <- fib1.await
          _  <- fib2.await
          v1 <- var1.get
          v2 <- var2.get
        } yield (v1, v2)
      } { result =>
        assertTrue(result == ((12, 22)))
      },
      testFlow(
        "retried transaction runs activity compensations in reverse order",
        periodicAdjustClock = Some(1.second)
      ) {

        val activity1Undo1 = Activity(
          "activity1Undo1",
          "activity1Undo1",
          Operation.Http(new URI("http://activity1/undo/1"), "GET", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.unit
        )
        val activity1Undo2 = Activity(
          "activity1Undo2",
          "activity1Undo2",
          Operation.Http(new URI("http://activity1/undo/2"), "GET", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.unit
        )
        val activity2Undo = Activity(
          "activity2Undo",
          "activity2Undo",
          Operation.Http(new URI("http://activity2/undo"), "GET", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.unit
        )

        val activity1 = Activity(
          "activity1",
          "activity1",
          Operation.Http(new URI("http://activity1"), "GET", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.input[Int].flatMap(n => activity1Undo1(n) *> activity1Undo2(n)).unit
        )
        val activity2 = Activity(
          "activity2",
          "activity2",
          Operation.Http(new URI("http://activity2"), "POST", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.input[Int].flatMap(n => activity2Undo(n)).unit
        )

        for {
          var1 <- ZFlow.newVar[Int]("var1", 1)
          fib1 <- ZFlow.transaction { _ =>
                    for {
                      v1 <- var1.get
                      _  <- ZFlow.log(s"Got value of var1")
                      _  <- activity1(v1)
                      _  <- activity2(v1)
                      _  <- var1.set(v1 + 1)
                    } yield ()
                  }.fork
          now <- ZFlow.now
          _   <- ZFlow.waitTill(now.plusSeconds(2L))
          _   <- ZFlow.log(s"Modifying value var1")
          _   <- var1.set(10)
          _   <- ZFlow.log(s"Modified value var1")
          _   <- fib1.await
        } yield ()
      }(
        result => assertTrue(result == (())),
        mock =
          // First run (with input=1)
          MockedOperation.Http(
            urlMatcher = equalTo(new URI("http://activity1")),
            methodMatcher = equalTo("GET"),
            inputMatcher = equalTo(1),
            result = () => 100,
            duration = 2.seconds
          ) ++
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity2")),
              methodMatcher = equalTo("POST"),
              inputMatcher = equalTo(1),
              result = () => 200,
              duration = 2.seconds
            ) ++
            // Revert
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity2/undo")),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(200),
              result = () => -2
            ) ++
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity1/undo/1")),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(100),
              result = () => -1
            ) ++
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity1/undo/2")),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(100),
              result = () => -11
            ) ++
            // Second run (with input=2)
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity1")),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(2),
              result = () => 100,
              duration = 10.millis
            ) ++
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity2")),
              methodMatcher = equalTo("POST"),
              inputMatcher = equalTo(2),
              result = () => 200,
              duration = 10.millis
            )
      ),
      testFlowExit(
        "failed transaction runs activity compensations in reverse order",
        periodicAdjustClock = Some(1.second)
      ) {

        val activity1Undo = Activity(
          "activity1Undo",
          "activity1Undo",
          Operation.Http(new URI("http://activity1/undo"), "GET", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.unit
        )
        val activity2Undo = Activity(
          "activity2Undo",
          "activity2Undo",
          Operation.Http(new URI("http://activity2/undo"), "GET", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.unit
        )

        val activity1 = Activity(
          "activity1",
          "activity1",
          Operation.Http(new URI("http://activity1"), "GET", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.input[Int].flatMap(n => activity1Undo(n)).unit
        )
        val activity2 = Activity(
          "activity2",
          "activity2",
          Operation.Http(new URI("http://activity2"), "POST", Map.empty, Schema[Int], Schema[Int]),
          ZFlow.succeed(0),
          ZFlow.input[Int].flatMap(n => activity2Undo(n)).unit
        )

        for {
          _ <- ZFlow.transaction { _ =>
                 for {
                   _ <- activity1(1)
                   _ <- activity2(1)
                   _ <- ZFlow.fail(ActivityError("simulated failure", None))
                 } yield ()
               }
        } yield ()
      }(
        result =>
          assert(result)(fails(equalTo(ActivityError("simulated failure", None)))) &&
            assert(result.causeOption.flatMap(_.dieOption))(isNone),
        mock =
          // First run
          MockedOperation.Http(
            urlMatcher = equalTo(new URI("http://activity1")),
            methodMatcher = equalTo("GET"),
            inputMatcher = equalTo(1),
            result = () => 100
          ) ++
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity2")),
              methodMatcher = equalTo("POST"),
              inputMatcher = equalTo(1),
              result = () => 200
            ) ++
            // Revert
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity2/undo")),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(200),
              result = () => -2
            ) ++
            MockedOperation.Http(
              urlMatcher = equalTo(new URI("http://activity1/undo")),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(100),
              result = () => -1
            )
      ),
      testFlowAndLogs("retry until", periodicAdjustClock = Some(200.millis)) {
        for {
          variable <- ZFlow.newVar("var", 0)
          fiber <- ZFlow.transaction { tx =>
                     for {
                       value <- variable.get
                       _     <- ZFlow.log("TX")
                       _     <- tx.retryUntil(value === 1)
                     } yield value
                   }.fork
          _      <- ZFlow.sleep(1.second)
          _      <- variable.set(1)
          _      <- ZFlow.log("Waiting for the transaction fiber to stop")
          result <- fiber.await
        } yield result
      } { (result, logs) =>
        assertTrue(
          result == Right(1),
          logs.filter(_ == "TX").size == 2
        )
      },
      testFlowAndLogs("orTry, second succeeds", periodicAdjustClock = Some(200.millis)) {
        for {
          variable <- ZFlow.newVar("var", 0)
          fiber <- ZFlow.transaction { tx =>
                     for {
                       value <- variable.get
                       _     <- ZFlow.log("TX1")
                       _ <- tx.retryUntil(value === 1).orTry {
                              ZFlow.log("TX2")
                            }
                     } yield 1
                   }.fork
          _      <- ZFlow.log("Waiting for the transaction fiber to stop")
          result <- fiber.await
        } yield result
      } { (result, logs) =>
        assertTrue(
          result == Right(1),
          logs.filter(_.startsWith("TX")) == Chunk("TX1", "TX2")
        )
      }
    ),
    testFlow("unwrap") {
      val flow = for {
        wrapped   <- ZFlow.input[ZFlow[Any, ZNothing, Int]]
        unwrapped <- ZFlow.unwrap(wrapped)
        result    <- unwrapped
      } yield result
      flow.provide(ZFlow.succeed(100))
    } { result =>
      assertTrue(result == 100)
    },
    test("fork/await") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = for {
                 flow1 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 2L)).as(1).fork
                 flow2 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 3L)).as(2).fork
                 r1    <- flow1.await
                 r2    <- flow2.await
                 _     <- ZFlow.log(r1.toString)
               } yield (r1.toOption, r2.toOption)
        fiber   <- flow.evaluateTestPersistent("fork").fork
        _       <- TestClock.adjust(3.seconds)
        result  <- fiber.join
        expected = (Some(1), Some(2))
      } yield assertTrue(result == expected)
    },
    testFlowAndLogs("fork/interrupt", periodicAdjustClock = Some(1.seconds)) {
      for {
        now   <- ZFlow.now
        flow1 <- (ZFlow.waitTill(now.plusSeconds(2L)) *> ZFlow.log("first")).fork
        flow2 <- (ZFlow.waitTill(now.plusSeconds(3L)) *> ZFlow.log("second")).fork
        flow3 <- (ZFlow.waitTill(now.plusSeconds(5L)) *> ZFlow.log("third")).fork
        _     <- flow1.await
        _     <- flow2.interrupt
        _     <- flow3.await
      } yield 111
    } { (result, logs) =>
      assertTrue(
        result == 111,
        logs.contains("first"),
        logs.contains("third"),
        !logs.contains("second")
      )
    },
    test("timeout works") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = ZFlow
                 .waitTill(Remote.ofEpochSecond(curr + 2L))
                 .as(1)
                 .timeout(Remote.ofSeconds(1L))
        fiber  <- flow.evaluateTestPersistent("timeout").fork
        _      <- TestClock.adjust(3.seconds)
        result <- fiber.join
      } yield assertTrue(result == None)
    },
    testFlow("timeout interrupts", periodicAdjustClock = Some(1.seconds)) {
      for {
        now <- ZFlow.now
        v   <- ZFlow.newVar[Boolean]("result", false)
        _ <- (for {
               _ <- ZFlow.waitTill(now.plusSeconds(2L))
               _ <- v.set(true)
             } yield ()).timeout(Remote.ofSeconds(1L))
        _ <- ZFlow.waitTill(now.plusSeconds(3L))
        r <- v.get
      } yield r
    } { result =>
      assertTrue(result == false)
    },
    testFlowExit[String, Nothing]("die") {
      ZFlow.fail("test").orDie
    } { (result: Exit[String, Nothing]) =>
      assert(result)(dies(hasMessage(equalTo("Could not evaluate ZFlow"))))
    }
    // TODO: retryUntil, orTry
  )

  val suite2 = suite("Restarted flows")(
    testRestartFlowAndLogs("log-|-log") { break =>
      for {
        _ <- ZFlow.log("first")
        _ <- break
        _ <- ZFlow.log("second")
      } yield 1
    } { (result, logs1, logs2) =>
      assertTrue(
        result == 1,
        logs1.contains("first"),
        !logs1.contains("second"),
        logs2.contains("second"),
        !logs2.contains("first")
      )
    },
    testRestartFlowAndLogs("newVar[Int]-|-get") { break =>
      for {
        v      <- ZFlow.newVar[Int]("var1", 100)
        _      <- break
        result <- v.get
      } yield result
    } { (result, _, _) =>
      assertTrue(
        result == 100
      )
    },
    testRestartFlowAndLogs("newVar[TestData]-|-get") { break =>
      for {
        v      <- ZFlow.newVar[TestData]("var1", TestData("test", 100))
        _      <- break
        result <- v.get
      } yield result
    } { (result, _, _) =>
      assertTrue(
        result == TestData("test", 100)
      )
    },
    testRestartFlowAndLogs("provide(log-|-input) (string)") { break =>
      (for {
        _ <- ZFlow.log("before break")
        _ <- break
        v <- ZFlow.input[String]
        _ <- ZFlow.log(v)
      } yield v).provide("abc")
    } { (result, logs1, logs2) =>
      assertTrue(
        result == "abc",
        logs1.contains("before break"),
        !logs2.contains("before break"),
        logs2.contains("abc"),
        !logs1.contains("abc")
      )
    },
    testRestartFlowAndLogs("provide(log-|-input) (user-defined)") { break =>
      (for {
        _ <- break
        v <- ZFlow.input[TestData]
      } yield v).provide(TestData("abc", 123))
    } { (result, _, _) =>
      assertTrue(
        result == TestData("abc", 123)
      )
    },
    testRestartFlowAndLogs("newVar,ensuring(set-|-set),get") { break =>
      for {
        v <- ZFlow.newVar[Int]("testvar", 1)
        _ <- (
               for {
                 _ <- v.set(10)
                 _ <- break
                 _ <- v.set(100)
               } yield ()
             ).ensuring {
               v.update(_ + 1)
             }
        r <- v.get
      } yield r
    } { (result, _, _) =>
      assertTrue(
        result == 101
      )
    },
    testRestartFlowAndLogs("fork-|-await") { break =>
      for {
        fiber <- (for {
                   _   <- ZFlow.log("fiber started")
                   now <- ZFlow.now
                   _ <- ZFlow.waitTill(now.plusSeconds(220L)) // wait 220s, must finish only after restart
                   _ <- ZFlow.log("fiber finished")
                 } yield 10).fork
        _ <- ZFlow.waitTill(Remote.ofEpochSecond(10L)) // wait for absolute T=10s
        _ <- break                                     // waits for 100s
        result <- fiber.await.timeout(Remote.ofSeconds(150L))
      } yield result
    } { (result, logs1, logs2) =>
      assertTrue(
        result == Some(Right(10)),
        logs1.contains("fiber started"),
        !logs1.contains("fiber finished"),
        !logs2.contains("fiber started"),
        logs2.contains("fiber finished")
      )
    }
  )

  override def spec =
    suite("All tests")(suite1, suite2)
      .provideCustom(
        IndexedStore.inMemory,
        DurableLog.live,
        KeyValueStore.inMemory,
        Runtime.addLogger(ZLogger.default.filterLogLevel(_ == LogLevel.Debug).map(_.foreach(println)))
      )

  private def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  private def testFlowAndLogsExit[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration]
  )(
    flow: ZFlow[Any, E, A]
  )(assert: (Exit[E, A], Chunk[String]) => TestResult, mock: MockedOperation) =
    test(label) {
      for {
        logQueue <- Queue.unbounded[String]
        runtime  <- ZIO.runtime[Any]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: Map[FiberRef[_], Any],
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = {
                     val msg = message()
                     runtime.unsafeRun(logQueue.offer(message()).unit)
                     msg
                   }
                 }
        fiber <-
          flow
            .evaluateTestPersistent("wf" + counter.incrementAndGet().toString, mock)
            .provideSomeLayer[DurableLog with KeyValueStore](Runtime.addLogger(logger))
            .exit
            .fork
        flowResult <- periodicAdjustClock match {
                        case Some(value) => waitAndPeriodicallyAdjustClock("flow result", 1.second, value)(fiber.join)
                        case None        => fiber.join
                      }
        logLines <- logQueue.takeAll
      } yield assert(flowResult, logLines)
    }

  private def testFlowAndLogs[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration] = None
  )(flow: ZFlow[Any, E, A])(assert: (A, Chunk[String]) => TestResult, mock: MockedOperation = MockedOperation.Empty) =
    testFlowAndLogsExit(label, periodicAdjustClock)(flow)(
      { case (exit, logs) =>
        exit.fold(cause => throw FiberFailure(cause), result => assert(result, logs))
      },
      mock
    )

  private def testFlow[E: Schema, A: Schema](label: String, periodicAdjustClock: Option[Duration] = None)(
    flow: ZFlow[Any, E, A]
  )(
    assert: A => TestResult,
    mock: MockedOperation = MockedOperation.Empty
  ) =
    testFlowAndLogs(label, periodicAdjustClock)(flow)({ case (result, _) => assert(result) }, mock)

  private def testFlowExit[E: Schema, A: Schema](label: String, periodicAdjustClock: Option[Duration] = None)(
    flow: ZFlow[Any, E, A]
  )(
    assert: Exit[E, A] => TestResult,
    mock: MockedOperation = MockedOperation.Empty
  ) =
    testFlowAndLogsExit(label, periodicAdjustClock)(flow)({ case (result, _) => assert(result) }, mock)

  private def testRestartFlowAndLogs[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, Nothing, Unit] => ZFlow[Any, E, A])(assert: (A, Chunk[String], Chunk[String]) => TestResult) =
    test(label) {
      for {
        _            <- ZIO.logDebug(s"=== testRestartFlowAndLogs $label started === ")
        logQueue     <- Queue.unbounded[String]
        runtime      <- ZIO.runtime[Any]
        breakPromise <- Promise.make[Nothing, Unit]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: Map[FiberRef[_], Any],
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = {
                     val msg = message()
                     runtime.unsafeRun {
                       msg match {
                         case "!!!BREAK!!!" => breakPromise.succeed(())
                         case _             => logQueue.offer(msg).unit
                       }
                     }
                     msg
                   }
                 }
        results <- {
          val break: ZFlow[Any, Nothing, Unit] =
            (ZFlow.log("!!!BREAK!!!") *>
              ZFlow.waitTill(Remote(Instant.ofEpochSecond(100))))
          val finalFlow = flow(break)
          for {
            fiber1 <- finalFlow
                        .evaluateTestPersistent(label)
                        .provideSomeLayer[DurableLog with KeyValueStore](Runtime.addLogger(logger))
                        .fork
            _ <- ZIO.logDebug(s"Adjusting clock by 20s")
            _ <- TestClock.adjust(20.seconds)
            _ <- waitAndPeriodicallyAdjustClock("break event", 1.second, 10.seconds) {
                   breakPromise.await
                 }
            _         <- ZIO.logDebug("Interrupting executor")
            _         <- fiber1.interrupt
            logLines1 <- logQueue.takeAll
            fiber2 <- finalFlow
                        .evaluateTestPersistent(label)
                        .provideSomeLayer[DurableLog with KeyValueStore](Runtime.addLogger(logger))
                        .fork
            _ <- ZIO.logDebug(s"Adjusting clock by 200s")
            _ <- TestClock.adjust(200.seconds)
            result <- waitAndPeriodicallyAdjustClock("executor to finish", 1.second, 10.seconds) {
                        fiber2.join
                      }
            logLines2 <- logQueue.takeAll
          } yield (result, logLines1, logLines2)
        }
      } yield assert.tupled(results)
    }

  private def waitAndPeriodicallyAdjustClock[E, A](
    description: String,
    duration: Duration,
    adjustment: Duration
  )(wait: ZIO[Any, E, A]): ZIO[Live, E, A] =
    for {
      _ <- ZIO.logDebug(s"Waiting for $description")
      liveClockLayer = ZLayer.scoped {
                         for {
                           clock <- Live.live(ZIO.clock)
                           _     <- ZEnv.services.locallyScopedWith(_.add(clock))
                         } yield ()
                       }
      maybeResult <- wait.timeout(1.second).provideSomeLayer[Live](liveClockLayer)
      result <- maybeResult match {
                  case Some(result) => ZIO.succeed(result)
                  case None =>
                    for {
                      _      <- ZIO.logDebug(s"Adjusting clock by $adjustment")
                      _      <- TestClock.adjust(adjustment)
                      now    <- Clock.instant
                      _      <- ZIO.logDebug(s"T=$now")
                      result <- waitAndPeriodicallyAdjustClock(description, duration, adjustment)(wait)
                    } yield result
                }
    } yield result

  case class TestData(a: String, b: Int)
  object TestData {
    implicit val schema: Schema[TestData] = DeriveSchema.gen
  }
}
