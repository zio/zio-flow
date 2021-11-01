package zio.flow

import zio.flow.remote.{Remote, _}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteInstantSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] = suite("RemoteInstantSpec")(
    testM("isAfter") {
      check(Gen.anyInstant, Gen.anyInstant) { case (i1, i2) =>
        (Remote(i1) isAfter Remote(i2)) <-> (i1 isAfter i2)
      }
    },
    testM("plusDuration") {
      check(Gen.anyInstant, Gen.anyInstant) { case (i1, i2) =>
        (Remote(i1) isBefore Remote(i2)) <-> (i1 isBefore i2)
      }
    },
    testM("plusDuration") {
      check(Gen.anyInstant, Gen.anyFiniteDuration) { case (i, d2) =>
        (Remote(i) plusDuration Remote(d2)) <-> (i plus d2)
      }
    },
    testM("getEpochSec") {
      check(Gen.anyInstant) { d =>
        Remote(d).getEpochSec <-> d.getEpochSecond
      }
    }
  ) @@ TestAspect.ignore
}
