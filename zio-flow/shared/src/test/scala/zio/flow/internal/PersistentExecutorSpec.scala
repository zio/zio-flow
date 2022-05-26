package zio.flow.internal

import zio._
import zio.flow._
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.{DeriveSchema, Schema}
import zio.ZNothing
import zio.test.Assertion.{dies, equalTo, hasMessage}
import zio.test.{Live, TestAspect, TestClock, TestResult, assert, assertTrue, live}

import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

object PersistentExecutorSpec extends ZIOFlowBaseSpec {

  private val unit: Unit = ()

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
      ZFlow.succeed(15)
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
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
    } { result =>
      assertTrue(result == unit)
    },
    testFlow("foldM - error side") {
      ZFlow
        .fail(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
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
    testFlow("Activity") {
      testActivity(12)
    } { result =>
      assertTrue(result == 12)
    },
    testFlow("iterate") {
      ZFlow.succeed(1).iterate[Any, ZNothing, Int](_ + 1)(_ !== 10)
    } { result =>
      assertTrue(result == 10)
    } @@ TestAspect.ignore, // TODO: fix recursion support
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
    testFlow("transaction") {
      // TODO: test transactional behavior
      ZFlow.transaction { _ =>
        ZFlow.succeed(100)
      }
    } { result =>
      assertTrue(result == 100)
    },
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
        Runtime.addLogger(ZLogger.default.map(println(_)))
      )

  private def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  private def testFlowAndLogsExit[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration] = None
  )(flow: ZFlow[Any, E, A])(assert: (Exit[E, A], Chunk[String]) => TestResult) =
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
            .evaluateTestPersistent(label)
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
  )(flow: ZFlow[Any, E, A])(assert: (A, Chunk[String]) => TestResult) =
    testFlowAndLogsExit(label, periodicAdjustClock)(flow) { case (exit, logs) =>
      exit.fold(cause => throw new FiberFailure(cause), result => assert(result, logs))
    }

  private def testFlow[E: Schema, A: Schema](label: String, periodicAdjustClock: Option[Duration] = None)(
    flow: ZFlow[Any, E, A]
  )(
    assert: A => TestResult
  ) =
    testFlowAndLogs(label, periodicAdjustClock)(flow) { case (result, _) => assert(result) }

  private def testFlowExit[E: Schema, A: Schema](label: String)(flow: ZFlow[Any, E, A])(
    assert: Exit[E, A] => TestResult
  ) =
    testFlowAndLogsExit(label)(flow) { case (result, _) => assert(result) }

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
                           clock <- live(ZIO.clock)
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
