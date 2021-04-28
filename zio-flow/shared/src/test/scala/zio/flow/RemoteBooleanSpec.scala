package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteBooleanSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] = suite("RemoteBooleanSpec")(
    test("And") {
      BoolAlgebra.all(
        (Remote(true) && Remote(false)) <-> false,
        (Remote(true) && Remote(true)) <-> true,
        (Remote(false) && Remote(false)) <-> false
      )
    },
    test("Or") {
      BoolAlgebra.all(
        (Remote(true) || Remote(false)) <-> true,
        (Remote(true) || Remote(true)) <-> true,
        (Remote(false) || Remote(false)) <-> false
      )
    },
    test("Not") {
      BoolAlgebra.all(
        !Remote(true) <-> false,
        !Remote(false) <-> true
      )
    }
  )

}
