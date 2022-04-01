package zio.flow.remote

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{Remote, RemoteContext}
import zio.test.{BoolAlgebra, Gen, Sized, check}
import zio.{Random, ZIO}

object RemoteRelationalSpec extends RemoteSpecBase {

  val smallIntGen: Gen[Random with Sized, Int] =
    Gen.small(Gen.const(_))

  override def spec =
    suite("RemoteRelationalSpec")(
      test("Int") {
        check(smallIntGen, smallIntGen) { case (x, y) =>
          ZIO
            .collectAll(
              List(
                Remote(x) < Remote(y) <-> (x < y),
                Remote(x) <= Remote(y) <-> (x <= y),
                (Remote(x) !== Remote(y)) <-> (x != y),
                Remote(x) > Remote(y) <-> (x > y),
                Remote(x) >= Remote(y) <-> (x >= y),
                (Remote(x) === Remote(y)) <-> (x == y)
              )
            )
            .map(BoolAlgebra.all(_))
            .map(_.get)
        }
      }
    ).provideCustom(RemoteContext.inMemory)
}
