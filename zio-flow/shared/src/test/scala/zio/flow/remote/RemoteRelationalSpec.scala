package zio.flow.remote

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{Remote, RemoteContext}
import zio.test.{TestResult, Gen, Sized, check}
import zio.ZIO

object RemoteRelationalSpec extends RemoteSpecBase {

  val smallIntGen: Gen[Sized, Int] =
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
            .map(TestResult.all(_: _*))
        }
      }
    ).provideCustom(RemoteContext.inMemory)
}
