package zio.flow

import zio._
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteRelationalSpec extends ZIOSpecDefault {

  val smallIntGen: Gen[Random with Sized, Int] =
    Gen.small(Gen.const(_))

  override def spec =
    suite("RemoteRelationalSpec")(
      test("Int") {
        check(smallIntGen, smallIntGen) { case (x, y) =>
          BoolAlgebra.all(
            Remote(x) < Remote(y) <-> (x < y),
            Remote(x) <= Remote(y) <-> (x <= y),
            (Remote(x) !== Remote(y)) <-> (x != y),
            Remote(x) > Remote(y) <-> (x > y),
            Remote(x) >= Remote(y) <-> (x >= y),
            (Remote(x) === Remote(y)) <-> (x == y)
          )
        }
      }
    )

}
