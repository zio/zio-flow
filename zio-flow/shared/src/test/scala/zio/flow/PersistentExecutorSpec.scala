package zio.flow

import zio.flow.ZFlowExecutorSpec.{isOdd, nothingSchema, testM}
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.random.Random
import zio.schema.Schema
import zio.test.Assertion.equalTo
import zio.test.{DefaultRunnableSpec, Gen, Spec, TestConfig, TestFailure, TestSuccess, ZSpec, assertM, checkM}

object PersistentExecutorSpec extends DefaultRunnableSpec {

  implicit val nothingSchema: Schema[Nothing]              = Schema.fail("Nothing schema")
  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  val suite1: Spec[Random with TestConfig, TestFailure[Nothing], TestSuccess] = suite("Test the easy operators")(testM("Test Return") {

    val flow: ZFlow[Any, Nothing, Int] = ZFlow.Return(12)
    assertM(flow.evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema))(equalTo(12))
  },
    testM("Test Modify : Int => Bool") {

        val compileResult = (for {
          variable         <- ZFlow.newVar[Int]("variable1", 10)
          modifiedVariable <- variable.modify(isOdd)
        } yield modifiedVariable).evaluateLivePersistent(implicitly[Schema[Boolean]], nothingSchema)
        assertM(compileResult)(equalTo(false))
    })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("All tests")(suite1)
}
