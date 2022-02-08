package zio.flow

import zio._
import zio.flow.ZFlowExecutorSpec.testActivity
import zio.flow.internal.{DurableLog, IndexedStore, KeyValueStore}
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.Schema
import zio.test.Assertion.{dies, equalTo, hasMessage}
import zio.test._

import java.time.Instant
import java.util.concurrent.TimeUnit

object PersistentExecutorSpec extends ZIOFlowBaseSpec {
  val suite1 = suite("Operators in single run")(
    testFlow("Return")(ZFlow.Return(12)) { result =>
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
      assertTrue(result == ())
    },
    testFlow("foldM - error side") {
      ZFlow
        .fail(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
    } { result =>
      assertTrue(result == ())
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
        flow.evaluateTestPersistent.map { result =>
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
        fiber  <- flow.evaluateTestPersistent.fork
        _      <- TestClock.adjust(3.seconds)
        result <- fiber.join
      } yield assertTrue(result.getEpochSecond == 2L)
    },
    testFlow("Activity") {
      testActivity(12)
    } { result =>
      assertTrue(result == 12)
    },
    testFlow("iterate") {
      ZFlow.succeed(1).iterate[Any, Nothing, Int](_ + 1)(_ !== 10)
    } { result =>
      assertTrue(result == 10)
    },
    testFlow("Modify") {
      for {
        variable <- ZFlow.newVar[Int]("var", 0)
        a        <- variable.modify(n => (n - 1, n + 1))
        b        <- variable.get
      } yield (a, b)
    }(result => assertTrue(result == (-1, 1))),
    testFlowAndLogs("log") {
      ZFlow.log("first message") *> ZFlow.log("second message").as(100)
    } { (result, logs) =>
      assertTrue(
        result == 100,
        logs == Chunk("first message", "second message")
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
        wrapped   <- ZFlow.input[ZFlow[Any, Nothing, Int]]
        unwrapped <- ZFlow.unwrap(wrapped)
        result    <- unwrapped
      } yield result
      flow.provide(ZFlow.succeed(100))
    } { result =>
      assertTrue(result == 100)
    },
    test("fork") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = for {
                 flow1 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 2L)).as(1).fork
                 flow2 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 3L)).as(2).fork
                 r1    <- flow1.await
                 r2    <- flow2.await
               } yield (r1.toOption, r2.toOption)
        fiber  <- flow.evaluateTestPersistent.fork
        _      <- TestClock.adjust(3.seconds)
        result <- fiber.join
      } yield assertTrue(result == (Some(1), Some(2)))
    },
    test("timeout") {
      for {
        curr <- Clock.currentTime(TimeUnit.SECONDS)
        flow = ZFlow
                 .waitTill(Remote.ofEpochSecond(curr + 2L))
                 .as(1)
                 .timeout(Remote.ofSeconds(1L))
        fiber  <- flow.evaluateTestPersistent.fork
        _      <- TestClock.adjust(3.seconds)
        result <- fiber.join
      } yield assertTrue(result == None)
    },
    testFlowExit[String, Nothing]("die") {
      ZFlow.fail("test").orDie
    } { (result: Exit[String, Nothing]) =>
      assert(result)(dies(hasMessage(equalTo("Could not evaluate ZFlow"))))
    }
    // TODO: retryUntil, orTry, interrupt, getExecutionEnvironment
  )

  // TODO: restart suite

  override def spec =
    suite("All tests")(suite1)
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
                     context: Map[ZFiberRef.Runtime[_], AnyRef],
                     spans: List[LogSpan],
                     location: ZTraceElement
                   ): String = {
                     val msg = message()
                     runtime.unsafeRun(logQueue.offer(message()).unit)
                     msg
                   }
                 }
        rc         <- ZIO.runtimeConfig
        flowResult <- flow.evaluateTestPersistent.withRuntimeConfig(rc @@ RuntimeConfigAspect.addLogger(logger)).exit
        logLines   <- logQueue.takeAll
      } yield assert(flowResult, logLines)
    }

  private def testFlowAndLogs[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, E, A])(assert: (A, Chunk[String]) => TestResult) =
    testFlowAndLogsExit(label)(flow) { case (exit, logs) =>
      exit.fold(cause => throw new FiberFailure(cause), result => assert(result, logs))
    }

  private def testFlow[E: Schema, A: Schema](label: String)(flow: ZFlow[Any, E, A])(assert: A => TestResult) =
    testFlowAndLogs(label)(flow) { case (result, _) => assert(result) }

  private def testFlowExit[E: Schema, A: Schema](label: String)(flow: ZFlow[Any, E, A])(
    assert: Exit[E, A] => TestResult
  ) =
    testFlowAndLogsExit(label)(flow) { case (result, _) => assert(result) }
}
