package zio.flow.internal

import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow._
import zio.schema.Schema
import zio.test.Assertion.{dies, equalTo, hasMessage}
import zio.test.{TestAspect, TestClock, TestResult, assert, assertTrue}
import zio._
import zio.stream.ZNothing

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
    } @@ TestAspect.ignore, // TODO: fix recursive schema serialization
    test("fork") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = for {
                 flow1 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 2L)).as(1).fork
                 flow2 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 3L)).as(2).fork
                 r1 <- flow1.await[ZNothing, Int] // TODO: avoid this explicit type annotation
                 r2 <- flow2.await[ZNothing, Int]
                 _  <- ZFlow.log(r1.toString)
               } yield (r1.toOption, r2.toOption)
        fiber   <- flow.evaluateTestPersistent("fork").fork
        _       <- TestClock.adjust(3.seconds)
        result  <- fiber.join
        expected = (Some(1), Some(2))
      } yield assertTrue(result == expected)
    },
    test("timeout") {
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
    test("timeout interrupts") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = for {
                 v <- ZFlow.newVar[Boolean]("result", false)
                 _ <- (for {
                        _ <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 2L))
                        _ <- v.set(true)
                      } yield ()).timeout(Remote.ofSeconds(1L))
                 _ <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 3L))
                 r <- v.get
               } yield r
        fiber  <- flow.evaluateTestPersistent("timeout-interrupts").fork
        _      <- TestClock.adjust(4.seconds)
        result <- fiber.join
      } yield assertTrue(result == false)
    } @@ TestAspect.ignore, // TODO: fix
    testFlowExit[String, Nothing]("die") {
      ZFlow.fail("test").orDie
    } { (result: Exit[String, Nothing]) =>
      assert(result)(dies(hasMessage(equalTo("Could not evaluate ZFlow"))))
    }
    // TODO: retryUntil, orTry, interrupt
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
    } @@ TestAspect.ignore // TODO: finish support for restarting persitent flows
  )

  override def spec =
    suite("All tests")(suite1, suite2)
      .provideCustom(
        IndexedStore.inMemory,
        DurableLog.live,
        KeyValueStore.inMemory
      )

  private def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  private def testFlowAndLogsExit[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, E, A])(assert: (Exit[E, A], Chunk[String]) => TestResult) =
    test(label) {
      for {
        logQueue <- ZQueue.unbounded[String]
        runtime  <- ZIO.runtime[Any]
        logger = new ZLogger[String, String] {
                   override def apply(
                     trace: ZTraceElement,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: Map[ZFiberRef.Runtime[_], AnyRef],
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = {
                     val msg = message()
                     runtime.unsafeRun(logQueue.offer(message()).unit)
                     msg
                   }
                 }
        rc <- ZIO.runtimeConfig
        flowResult <-
          flow.evaluateTestPersistent(label).withRuntimeConfig(rc @@ RuntimeConfigAspect.addLogger(logger)).exit
        logLines <- logQueue.takeAll
      } yield assert(flowResult, logLines)
    }

  private def testFlowAndLogs[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, E, A])(assert: (A, Chunk[String]) => TestResult) =
    testFlowAndLogsExit(label)(flow) { case (exit, logs) =>
      exit.fold(cause => throw new FiberFailure(cause), result => assert(result, logs))
    }

  private def testFlow[E: Schema, A: Schema](label: String)(flow: ZFlow[Any, E, A])(
    assert: A => TestResult
  ) =
    testFlowAndLogs(label)(flow) { case (result, _) => assert(result) }

  private def testFlowExit[E: Schema, A: Schema](label: String)(flow: ZFlow[Any, E, A])(
    assert: Exit[E, A] => TestResult
  ) =
    testFlowAndLogsExit(label)(flow) { case (result, _) => assert(result) }

  private def testRestartFlowAndLogs[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, Nothing, Unit] => ZFlow[Any, E, A])(assert: (A, Chunk[String], Chunk[String]) => TestResult) =
    test(label) {
      for {
        logQueue     <- ZQueue.unbounded[String]
        runtime      <- ZIO.runtime[Any]
        breakPromise <- Promise.make[Nothing, Unit]
        logger = new ZLogger[String, String] {
                   override def apply(
                     trace: ZTraceElement,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: Map[ZFiberRef.Runtime[_], AnyRef],
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
        rc <- ZIO.runtimeConfig
        results <- {
          val break: ZFlow[Any, Nothing, Unit] =
            (ZFlow.log("!!!BREAK!!!") *>
              ZFlow.waitTill(Remote(Instant.ofEpochSecond(100))))
          val finalFlow = flow(break)
          for {
            fiber1 <- finalFlow
                        .evaluateTestPersistent(label)
                        .withRuntimeConfig(rc @@ RuntimeConfigAspect.addLogger(logger))
                        .fork
            _         <- TestClock.adjust(50.seconds)
            _         <- breakPromise.await
            _         <- fiber1.interrupt
            logLines1 <- logQueue.takeAll
            fiber2 <- finalFlow
                        .evaluateTestPersistent(label)
                        .withRuntimeConfig(rc @@ RuntimeConfigAspect.addLogger(logger))
                        .fork
            _         <- TestClock.adjust(200.seconds)
            result    <- fiber2.join
            logLines2 <- logQueue.takeAll
          } yield (result, logLines1, logLines2)
        }
      } yield assert.tupled(results)
    }
}
