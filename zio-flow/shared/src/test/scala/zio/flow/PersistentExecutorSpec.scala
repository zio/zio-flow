package zio.flow

import zio.flow.ZFlowExecutorSpec.{isOdd, nothingSchema, testM}
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.random.Random
import zio.schema.Schema
import zio.test.Assertion.equalTo
import zio.test.TestAspect.ignore
import zio.test.{DefaultRunnableSpec, Gen, Spec, TestConfig, TestFailure, TestSuccess, ZSpec, assertM, checkM}

object PersistentExecutorSpec extends DefaultRunnableSpec {

  implicit val nothingSchema: Schema[Nothing]              = Schema.fail("Nothing schema")
  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  val suite1 = suite("Test the easy operators")(testM("Test Return") {

    val flow: ZFlow[Any, Nothing, Int] = ZFlow.Return(12)
    assertM(flow.evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema))(equalTo(12))
  },
    testM("Test NewVar") {

        val compileResult = (for {
          variable         <- ZFlow.newVar[Int]("variable1", 10)
          //modifiedVariable <- variable.modify(isOdd)
          v <- variable.get
        } yield v).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult)(equalTo(10))
    } @@ignore,
    testM("Test Fold - success side") {
      val compileResult = ZFlow.succeed(15).foldM(_ => ZFlow.unit, _ => ZFlow.unit).evaluateLivePersistent(implicitly[Schema[Unit]], nothingSchema)
      assertM(compileResult)(equalTo(()))
    } @@ignore,
    testM("Test input"){
      val compileResult = ZFlow.input[Int].provide(12).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
      assertM(compileResult)(equalTo(12))
    },
    testM("Test input with function"){
      val compileResult = (for {
        a <- ZFlow.input[Int]
        r <- (a+10).provide(13)
      } yield r).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)

      assertM(compileResult)(equalTo(23))
    })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("All tests")(suite1)
}
