package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteStringSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] = suite("RemoteStringSpec")(
    test("CharAt") {
      BoolAlgebra.all(
        Remote("abc").charAtOption(0) <-> Some('a'),
        Remote("abc").charAtOption(1) <-> Some('b'),
        Remote("abc").charAtOption(2) <-> Some('c'),
        Remote("abc").charAtOption(-1) <-> None,
        Remote("abc").charAtOption(3) <-> None
      )
    },
    test("CodepointAt") {
      BoolAlgebra.all(
        Remote("abc").codepointAtOption(0) <-> Some(97),
        Remote("abc").codepointAtOption(1) <-> Some(98),
        Remote("abc").codepointAtOption(2) <-> Some(99),
        Remote("abc").codepointAtOption(-1) <-> None,
        Remote("abc").codepointAtOption(3) <-> None
      )
    },
    test("CodepointBefore") {
      BoolAlgebra.all(
        Remote("abc").codepointBeforeOption(0) <-> None,
        Remote("abc").codepointBeforeOption(1) <-> Some(97),
        Remote("abc").codepointBeforeOption(2) <-> Some(98),
        Remote("abc").codepointBeforeOption(3) <-> Some(99),
        Remote("abc").codepointAtOption(-1) <-> None
      )
    },
    test("Compare") {
      BoolAlgebra.all(
        Remote("a").compare("a") <-> 0,
        Remote("a").compare("b") <-> -1,
        Remote("b").compare("a") <-> 1
      )
    },
    test("Concat") {
      BoolAlgebra.all(
        Remote("abc") ++ Remote("123") <-> "abc123",
        Remote("123") ++ Remote("abc") <-> "123abc",
        Remote("abc") ++ Remote("") <-> "abc",
        Remote("") ++ Remote("abc") <-> "abc"
      )
    },
    test("Reverse") {
      Remote("foo").reverse <-> "oof"
    },
    test("Reverse empty string") {
      Remote("").reverse <-> ""
    }
  )
}
