package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteOptionSpec extends ZIOSpecDefault {
  val suite1: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("RemoteOptionSpec")(
    test("HandleOption for Some") {
      val option: Remote[Option[Int]] = Remote(Option(12))
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
      val op2 = Remote(Option(12))
      BoolAlgebra.all(
        op1.isSome <-> false,
        op2.isSome <-> true
      )
    },
    test("isNone") {
      val op1 = Remote(None)
      val op2 = Remote(Option(12))
      BoolAlgebra.all(
        op1.isNone <-> true,
        op2.isNone <-> false
      )
    },
    test("isEmpty") {
      val op1 = Remote(None)
      val op2 = Remote(Option(12))
      BoolAlgebra.all(
        op1.isEmpty <-> true,
        op2.isEmpty <-> false
      )
    },
    test("isDefined") {
      val op1 = Remote(None)
      val op2 = Remote(Option(12))
      BoolAlgebra.all(
        op1.isDefined <-> false,
        op2.isDefined <-> true
      )
    },
    test("knownSize") {
      val op1 = Remote(None)
      val op2 = Remote(Option(12))
      BoolAlgebra.all(
        op1.knownSize <-> 0,
        op2.knownSize <-> 1
      )
    },
    test("knownSize") {
      val op1 = Remote(None)
      val op2 = Remote(Option(12))
      BoolAlgebra.all(
        op1.contains(2) <-> false,
        op2.contains(12) <-> true,
        op2.contains(11) <-> false
      )
    },
    test("orElse") {
      val op1 = Remote(None)
      val op2 = Remote(Option(12))
      BoolAlgebra.all(
        op1.orElse(Option(2)) <-> Option(2),
        op2.orElse(Option(2)) <-> Option(12)
      )
    },
    test("OptionSpec") {
      val op1 = Remote(None)
      val op2 = Remote(Option(12))
      val op3 = Remote(Option(10))
      BoolAlgebra.all(
        op1.zip(op3) <-> None,
        op3.zip(op1) <-> None,
        op2.zip(op3) <-> Some((12, 10))
      )
    }
  )
  override def spec = suite("OptionSpec")(suite1)
}
