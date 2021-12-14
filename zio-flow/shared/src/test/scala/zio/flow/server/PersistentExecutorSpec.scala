package zio.flow.server

import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow.{Remote, ZFlow}
import zio.schema.Schema
import zio.test.Assertion.equalTo
import zio.test.TestAspect.ignore
import zio.test._
import zio.test.{Annotations, Spec, TestFailure, TestSuccess, ZSpec, suite}

object PersistentExecutorSpec extends DefaultRunnableSpec {

  implicit val nothingSchema: Schema[Nothing]               = Schema.fail("Nothing schema")
  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  val suite1: Spec[Annotations, TestFailure[Any], TestSuccess] = suite("Test the easy operators")(
    testM("Test Return") {
      val flow: ZFlow[Any, Nothing, Int] = ZFlow.Return(12)
      assertM(flow.evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema))(equalTo(12))
    },
    testM("Test NewVar") {
      val compileResult = (for {
        variable <- ZFlow.newVar[Int]("variable1", 10)
        //modifiedVariable <- variable.modify(isOdd)
        v        <- variable.get
      } yield v).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(10))
    } @@ ignore,
    testM("Test Fold - success side") {
      val compileResult = ZFlow
        .succeed(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
        .evaluateLivePersistent(implicitly[Schema[Unit]], nothingSchema)
      assertM(compileResult)(equalTo(()))
    } @@ignore,
    testM("Test Fold - error side") {
      val compileResult = ZFlow
        .fail(15)
        .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
        .evaluateLivePersistent(implicitly[Schema[Unit]], nothingSchema)
      assertM(compileResult)(equalTo(()))
    } @@ignore,
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
    } @@ ignore,
    testM("Test Provide") {
      val compileResult = ZFlow.succeed(12).provide(15).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    }
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("All tests")(suite1)
}
