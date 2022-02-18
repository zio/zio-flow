package zio.flow

import zio.ZIO
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteOptionSpec extends RemoteSpecBase {
  val suite1: Spec[TestEnvironment, TestFailure[Any], TestSuccess] =
    suite("RemoteOptionSpec")(
      test("HandleOption for Some") {
        val option: Remote[Option[Int]] = Remote(Option(12))
        val optionHandled: Remote[Int]  = option.handleOption(Remote(0), (x: Remote[Int]) => x * 2)
        optionHandled <-> 24
      },
      test("HandleOption for None") {
        val option        = Remote[Option[Int]](None)
        val optionHandled = option.handleOption(Remote(0), (x: Remote[Int]) => x * 2)
        optionHandled <-> 0
      },
      test("isSome") {
        val op1 = Remote[Option[Int]](None)
        val op2 = Remote(Option(12))
        ZIO
          .collectAll(
            List(
              op1.isSome <-> false,
              op2.isSome <-> true
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("isNone") {
        val op1 = Remote[Option[Int]](None) // TODO: otherwise diverging implicits. Should Remote be invariant?
        val op2 = Remote(Option(12))
        ZIO
          .collectAll(
            List(
              op1.isNone <-> true,
              op2.isNone <-> false
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("isEmpty") {
        val op1 = Remote[Option[Int]](None)
        val op2 = Remote(Option(12))
        ZIO
          .collectAll(
            List(
              op1.isEmpty <-> true,
              op2.isEmpty <-> false
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("isDefined") {
        val op1 = Remote[Option[Int]](None)
        val op2 = Remote(Option(12))
        ZIO
          .collectAll(
            List(
              op1.isDefined <-> false,
              op2.isDefined <-> true
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("knownSize") {
        val op1 = Remote[Option[Int]](None)
        val op2 = Remote(Option(12))
        ZIO
          .collectAll(
            List(
              op1.knownSize <-> 0,
              op2.knownSize <-> 1
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("knownSize") {
        val op1 = Remote[Option[Int]](None)
        val op2 = Remote(Option(12))
        ZIO
          .collectAll(
            List(
              op1.contains(2) <-> false,
              op2.contains(12) <-> true,
              op2.contains(11) <-> false
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("orElse") {
        val op1 = Remote[Option[Int]](None)
        val op2 = Remote(Option(12))
        ZIO
          .collectAll(
            List(
              op1.orElse(Option(2)) <-> Option(2),
              op2.orElse(Option(2)) <-> Option(12)
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("OptionSpec") {
        val op1 = Remote[Option[Int]](None)
        val op2 = Remote(Option(12))
        val op3 = Remote(Option(10))
        ZIO
          .collectAll(
            List(
              op1.zip(op3) <-> None,
              op3.zip(op1) <-> None,
              op2.zip(op3) <-> Some((12, 10))
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      }
    ).provideCustom(RemoteContext.inMemory)

  override def spec = suite("OptionSpec")(suite1)
}
