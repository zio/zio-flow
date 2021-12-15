package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.random.Random
import zio.test._

object RemoteRelationalSpec extends DefaultRunnableSpec {

  val smallIntGen: Gen[Random with Sized, Int] =
    Gen.small(Gen.const(_))

  override def spec: ZSpec[TestConfig with Random with Annotations with Sized, Any] =
    suite("RemoteRelationalSpec")(
      testM("Int") {
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
