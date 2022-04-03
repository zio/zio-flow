package zio.flow.remote

import zio.ZIO
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{Remote, RemoteContext}
import zio.test.BoolAlgebra

object RemoteBooleanSpec extends RemoteSpecBase {
  override def spec =
    suite("RemoteBooleanSpec")(
      test("And") {
        ZIO
          .collectAll(
            List(
              (Remote(true) && Remote(false)) <-> false,
              (Remote(true) && Remote(true)) <-> true,
              (Remote(false) && Remote(false)) <-> false
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("Or") {
        ZIO
          .collectAll(
            List(
              (Remote(true) || Remote(false)) <-> true,
              (Remote(true) || Remote(true)) <-> true,
              (Remote(false) || Remote(false)) <-> false
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("Not") {
        ZIO
          .collectAll(
            List(
              !Remote(true) <-> false,
              !Remote(false) <-> true
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("IfThenElse") {
        ZIO
          .collectAll(
            List(
              Remote(false).ifThenElse(Remote(1), Remote(12)) <-> 12,
              Remote(true).ifThenElse(Remote(1), Remote(12)) <-> 1
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      }
    ).provide(RemoteContext.inMemory)

}
