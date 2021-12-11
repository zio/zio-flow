package zio.flow

import java.time.Instant

import zio.flow.ZFlowExecutorSpec.testActivity
import zio.flow.remote._
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow.zFlow.ZFlow
import zio.schema.Schema
import zio.test.Assertion._
import zio.test._

object PersistentExecutorSpec extends ZIOFlowBaseSpec {

  implicit val nothingSchema: Schema[Nothing]              = Schema.fail("Nothing schema")
  implicit val acitivityErrorSchema: Schema[ActivityError] = Schema.fail("Activity Error schema")

  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  val suite1: Spec[Annotations, TestFailure[Any], TestSuccess] = suite("Test the easy operators")(
    testM("Test Return") {

      val flow: ZFlow[Any, Nothing, Int] = ZFlow.Return(12)
      assertM(flow.evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema))(equalTo(12))
    },
    testM("Test NewVar") {
      val compileResult = (for {
        variable         <- ZFlow.newVar("variable1", 10)
        modifiedVariable <- variable.modify(isOdd)
        v                <- modifiedVariable
      } yield v).evaluateLivePersistent(implicitly[Schema[Boolean]], nothingSchema)
      assertM(compileResult)(equalTo(false))
    },
    testM("Test Fold - success side") {
      val compileResult = ZFlow
        .succeed(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
        .evaluateLivePersistent(implicitly[Schema[Unit]], implicitly[Schema[Int]])
      assertM(compileResult)(equalTo(()))
    },
    testM("Test Fold - error side") {
      val compileResult = ZFlow
        .fail(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
        .evaluateLivePersistent(implicitly[Schema[Unit]], nothingSchema)
      assertM(compileResult)(equalTo(()))
    },
    testM("Test Fold") {
      val compileResult =
        ZFlow.succeed(12).flatMap(rA => rA + Remote(1)).evaluateLivePersistent(implicitly[Schema[Int]], implicitly[Schema[Int]])
      assertM(compileResult)(equalTo(13))
    },
    testM("Test input") {
      val compileResult = ZFlow.input[Int].provide(12).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test flatmap") {
      val compileResult = (for {
        a <- ZFlow.succeed(12)
        //b <- ZFlow.succeed(10)
      } yield a).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Provide") {
      val compileResult = ZFlow.succeed(12).provide(15).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Ensuring") {
      val compileResult =
        ZFlow.succeed(12).ensuring(ZFlow.succeed(15)).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Provide - advanced") {
      val zflow1        = for {
        a <- ZFlow.succeed(15)
        b <- ZFlow.input[Int]
      } yield a + b
      val compileResult = zflow1.provide(11).evaluateLivePersistent(Schema[Int], nothingSchema)
      assertM(compileResult)(equalTo(26))
    },
    testM("Test Now") {
      val compileResult = ZFlow.now.evaluateLivePersistent(Schema[Instant], nothingSchema)
      assertM(compileResult.map(i => i.getEpochSecond))(equalTo(Instant.now.getEpochSecond))
    },
    testM("Test WaitTill") {
      val twoSecsLater  = Instant.now().getEpochSecond + 2
      val compileResult =
        ZFlow.waitTill(Remote(Instant.ofEpochSecond(twoSecsLater))).evaluateLivePersistent(Schema[Unit], nothingSchema)
      assertM(compileResult)(equalTo(()))
    },
    testM("Test Activity") {
      val compileResult = ZFlow.RunActivity(12, testActivity).evaluateLivePersistent(Schema[Int], Schema[ActivityError])
      assertM(compileResult)(equalTo(12))
    },
    testM("Test Iterate") {
      val flow = ZFlow.succeed(1).iterate[Any, Nothing, Int](_ + 1)(_ !== 10)
      val compileResult = flow.evaluateLivePersistent(Schema[Int], nothingSchema)
      assertM(compileResult)(equalTo(10))
    }
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("All tests")(suite1)
}
