package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteDurationSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] = suite("RemoteDurationSpec")(
    testM("plusDuration2") {
      check(Gen.anyFiniteDuration, Gen.anyFiniteDuration) { case (d1, d2) =>
        (Remote(d1) plusDuration2 Remote(d2)) <-> (d1 plus d2)
      }
    },
    testM("minusDuration") {
      check(Gen.anyFiniteDuration, Gen.anyFiniteDuration) { case (d1, d2) =>
        (Remote(d1) minusDuration Remote(d2)) <-> (d1 minus d2)
      }
    },
    testM("toSeconds") {
      check(Gen.anyFiniteDuration) { d =>
        Remote(d).toSeconds <-> d.getSeconds
      }
    }
  ) @@ TestAspect.ignore

}
