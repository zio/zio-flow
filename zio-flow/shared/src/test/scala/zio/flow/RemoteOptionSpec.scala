package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test.{ DefaultRunnableSpec, ZSpec, _ }

object RemoteOptionSpec extends DefaultRunnableSpec {
  val suite1: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("RemoteOptionSpec")(
    test("HandleOption for Some") {
      val option: Remote[Option[Int]] = Remote(Some(12))
      val optionHandled: Remote[Int]  = option.handleOption(Remote(0), (x: Remote[Int]) => x * 2)
      optionHandled <-> 24
    },
    test("HandleOption for None") {
      val option        = Remote(None)
      val optionHandled = option.handleOption(Remote(0), (x: Remote[Int]) => x * 2)
      optionHandled <-> 0
    },
    test("isSome") {
      val op1 = Remote(None)
      val op2 = Remote(Some(12))
      BoolAlgebra.all(
        op1.isSome <-> false,
        op2.isSome <-> true
      )
    },
    test("isNone") {
      val op1 = Remote(None)
      val op2 = Remote(Some(12))
      BoolAlgebra.all(
        op1.isNone <-> true,
        op2.isNone <-> false
      )
    }
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("OptionSpec")(suite1)
}
