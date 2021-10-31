package zio.flow.remote

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.random.Random
import zio.test._

object RemoteRelationalSpec extends DefaultRunnableSpec {

  val lessThanIntGen: Gen[Random, (Int, Int)] = for {
    x <- Gen.anyInt
    y <- Gen.int(x + 1, Int.MaxValue)
  } yield (x, y)

  override def spec: ZSpec[TestConfig with Random with Annotations, Any] =
    suite("RemoteRelationalSpec")(
      testM("Int") {
        check(lessThanIntGen) { case (x, y) =>
          BoolAlgebra.all(
            Remote(x) < Remote(y) <-> true,
            Remote(x) <= Remote(y) <-> true,
            (Remote(x) !== Remote(y)) <-> true,
            Remote(x) > Remote(y) <-> false,
            Remote(x) >= Remote(y) <-> false,
            (Remote(x) === Remote(y)) <-> false
          )
        }
      }
    ) @@ TestAspect.ignore

}
