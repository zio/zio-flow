package zio.flow

import zio.clock.currentTime
import zio.duration.durationInt
import zio.flow.ZFlowExecutorSpec.testActivity
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.Schema
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestClock

import java.time.Instant
import java.util.concurrent.TimeUnit

object PersistentExecutorSpec extends ZIOFlowBaseSpec {

  implicit val nothingSchema: Schema[Nothing] = Schema.fail("Nothing schema")

  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  val suite1 = suite("Test the easy operators")(
    testM("Test Return") {

      val flow: ZFlow[Any, Nothing, Int] = ZFlow.Return(12)
      assertM(flow.evaluateTestPersistent(implicitly[Schema[Int]], nothingSchema))(equalTo(12))
    },
    testM("Test NewVar") {
      val compileResult = (for {
        variable         <- ZFlow.newVar("variable1", 10)
        modifiedVariable <- variable.modify(isOdd)
        v                <- modifiedVariable
      } yield v).evaluateTestPersistent(implicitly[Schema[Boolean]], nothingSchema)
      assertM(compileResult)(equalTo(false))
    },
    testM("Test Fold - success side") {
      val compileResult = ZFlow
        .succeed(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
        .evaluateTestPersistent(implicitly[Schema[Unit]], implicitly[Schema[Int]])
      assertM(compileResult)(equalTo(()))
    },
    testM("Test Fold - error side") {
      val compileResult = ZFlow
        .fail(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
        .evaluateTestPersistent(implicitly[Schema[Unit]], nothingSchema)
      assertM(compileResult)(equalTo(()))
    },
    testM("Test Fold") {
      val compileResult =
        ZFlow
          .succeed(12)
          .flatMap(rA => rA + Remote(1))
          .evaluateTestPersistent(implicitly[Schema[Int]], implicitly[Schema[Int]])
      assertM(compileResult)(equalTo(13))
    },
    testM("Test input") {
      val compileResult = ZFlow.input[Int].provide(12).evaluateTestPersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test flatmap") {
      val compileResult = (for {
        a <- ZFlow.succeed(12)
        //b <- ZFlow.succeed(10)
      } yield a).evaluateTestPersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Provide") {
      val compileResult = ZFlow.succeed(12).provide(15).evaluateTestPersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Ensuring") {
      val compileResult =
        ZFlow.succeed(12).ensuring(ZFlow.succeed(15)).evaluateTestPersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Provide - advanced") {
      val zflow1 = for {
        a <- ZFlow.succeed(15)
        b <- ZFlow.input[Int]
      } yield a + b
      val compileResult = zflow1.provide(11).evaluateTestPersistent(Schema[Int], nothingSchema)
      assertM(compileResult)(equalTo(26))
    },
    testM("Test Now") {
      val compileResult = ZFlow.now.evaluateTestPersistent(Schema[Instant], nothingSchema)
      assertM(compileResult.map(i => i.getEpochSecond))(equalTo(0L))
    },
    testM("Test WaitTill") {
      for {
        curr <- currentTime(TimeUnit.SECONDS)
        _    <- TestClock.adjust(3.seconds)
        compileResult <-
          ZFlow.waitTill(Remote(Instant.ofEpochSecond(curr + 2L))).evaluateTestPersistent(Schema[Unit], nothingSchema)
      } yield assert(compileResult)(equalTo(()))
    },
    testM("Test Activity") {
      val compileResult = ZFlow.RunActivity(12, testActivity).evaluateTestPersistent(Schema[Int], Schema[ActivityError])
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Iterate") {
      val flow          = ZFlow.succeed(1).iterate[Any, Nothing, Int](_ + 1)(_ !== 10)
      val compileResult = flow.evaluateTestPersistent(Schema[Int], nothingSchema)
      assertM(compileResult)(equalTo(10))
    }
  )

  val suite2 = suite("Test the easy operators")(testM("Test Return") {

    val flow: ZFlow[Any, Nothing, Int] = ZFlow.Return(12)
    assertM(flow.evaluateTestPersistent(implicitly[Schema[Int]], nothingSchema))(equalTo(12))
  })

  override def spec = suite("All tests")(suite1)
}
