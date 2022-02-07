package zio.flow

import zio._
import zio.flow.ZFlowExecutorSpec.testActivity
import zio.flow.internal.{DurableLog, IndexedStore, KeyValueStore}
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.Schema
import zio.test._

import java.time.Instant
import java.util.concurrent.TimeUnit

object PersistentExecutorSpec extends ZIOFlowBaseSpec {
  val suite1 = suite("Operators")(
    testFlow("Return")(ZFlow.Return(12)) { result =>
      assertTrue(result == 12)
    },
    testFlow("NewVar") {
      for {
        variable         <- ZFlow.newVar("variable1", 10)
        modifiedVariable <- variable.modify(isOdd)
        v                <- modifiedVariable
      } yield v
    } { result =>
      assertTrue(result == false)

    },
    testFlow("Fold - success side") {
      ZFlow
        .succeed(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
    } { result =>
      assertTrue(result == ())
    },
    testFlow("Fold - error side") {
      ZFlow
        .fail(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
    } { result =>
      assertTrue(result == ())
    },
    testFlow("Fold") {
      ZFlow
        .succeed(12)
        .flatMap(rA => rA + Remote(1))
    } { result =>
      assertTrue(result == 13)
    },
    testFlow("input") {
      ZFlow.input[Int].provide(12)
    } { result =>
      assertTrue(result == 12)
    },
    testFlow("flatmap") {
      for {
        a <- ZFlow.succeed(12)
        b <- ZFlow.succeed(a + 10)
      } yield b
    } { result =>
      assertTrue(result == 22)
    },
    testFlow("Ensuring") {
      for {
        res <- ZFlow.newVar[Int]("res", 0)
        a   <- ZFlow.succeed(12).ensuring(res.set(15))
        b   <- res.get
      } yield a + b
    } { result =>
      assertTrue(result == 27)
    },
    testFlow("Provide") {
      {
        for {
          a <- ZFlow.succeed(15)
          b <- ZFlow.input[Int]
        } yield a + b
      }.provide(11)
    } { result =>
      assertTrue(result == 26)
    },
    test("Now") {
      TestClock.adjust(5.seconds) *> {
        val flow = ZFlow.now
        flow.evaluateTestPersistent.map { result =>
          assertTrue(result.getEpochSecond == 5L)
        }
      }
    },
    test("WaitTill") {
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
      ZFlow.RunActivity(12, testActivity)
    } { result =>
      assertTrue(result == 12)
    },
    testFlow("Iterate") {
      ZFlow.succeed(1).iterate[Any, Nothing, Int](_ + 1)(_ !== 10)
    } { result =>
      assertTrue(result == 10)
    }
  )

  override def spec =
    suite("All tests")(suite1)
      .provideCustom(
        IndexedStore.inMemory,
        DurableLog.live,
        KeyValueStore.inMemory
      )

  private def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  private def testFlow[E: Schema, A: Schema](label: String)(flow: ZFlow[Any, E, A])(assert: A => Assert) =
    test(label)(flow.evaluateTestPersistent.map(assert))
}
