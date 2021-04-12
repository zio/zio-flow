package zio.flow

import zio.test.Assertion.equalTo
import zio.test.{ DefaultRunnableSpec, ZSpec, _ }

object RemoteOptionSpec extends DefaultRunnableSpec {
  val suite1: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("RemoteOptionSpec")(
    test("HandleOption for Some") {
      val option: Remote[Option[Int]] = Remote(Some(12))
      val optionHandled: Remote[Int]  = option.handleOption(Remote(0), (x: Remote[Int]) => x * 2)
      assert(optionHandled.eval)(equalTo(Right(24)))
    },
    test("HandleOption for None") {
      val option        = Remote(None)
      val optionHandled = option.handleOption(Remote(0), (x: Remote[Int]) => x * 2)
      assert(optionHandled.eval)(equalTo(Right(0)))
    },
    test("isSome") {
      val op1 = Remote(None)
      assert(op1.isSome.eval)(equalTo(Right(false)))
      val op2 = Remote(Some(12))
      assert(op2.isSome.eval)(equalTo(Right(true)))
    },
    test("isNone") {
      val op1 = Remote(None)
      assert(op1.isNone.eval)(equalTo(Right(true)))
      val op2 = Remote(Some(12))
      assert(op2.isNone.eval)(equalTo(Right(false)))
    }
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("OptionSpec")(suite1)
}
