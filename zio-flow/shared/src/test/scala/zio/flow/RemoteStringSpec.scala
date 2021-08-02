package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteStringSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] = suite("RemoteStringSpec")(
    test("Reverse") {
      Remote("foo").reverse <-> "oof"
    },
    test("Reverse empty string") {
      Remote("").reverse <-> ""
    },
    test("Concat") {
      BoolAlgebra.all(
        Remote("abc") ++ Remote("123") <-> "abc123",
        Remote("123") ++ Remote("abc") <-> "123abc"
      )
    },
    test("Concat empty string") {
      BoolAlgebra.all(
        Remote("abc") ++ Remote("") <-> "abc",
        Remote("") ++ Remote("abc") <-> "abc"
      )
    }
  )
}
