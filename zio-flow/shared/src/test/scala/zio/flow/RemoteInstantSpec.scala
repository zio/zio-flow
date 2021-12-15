package zio.flow

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
    },
    testM("plusSeconds") {
      check(Gen.anyInstant, Gen.anyLong) { case (i, s) =>
        Remote(i).plusSeconds(Remote(s)) <-> i.plusSeconds(s)
      }
    },
    testM("plusMills") {
      check(Gen.anyInstant, Gen.anyLong) { case (i, ms) =>
        Remote(i).plusMillis(Remote(ms)) <-> i.plusMillis(ms)
      }
    },
    testM("plusNanos") {
      check(Gen.anyInstant, Gen.anyLong) { case (i, ns) =>
        Remote(i).plusMillis(Remote(ns)) <-> i.plusNanos(ns)
      }
    },
    testM("minusSeconds") {
      check(Gen.anyInstant, Gen.anyLong) { case (i, s) =>
        Remote(i).minusSeconds(Remote(s)) <-> i.minusSeconds(s)
      }
    },
    testM("minusMills") {
      check(Gen.anyInstant, Gen.anyLong) { case (i, ms) =>
        Remote(i).minusMillis(Remote(ms)) <-> i.minusMillis(ms)
      }
    },
    testM("minusNanos") {
      check(Gen.anyInstant, Gen.anyLong) { case (i, ns) =>
        Remote(i).minusNanos(Remote(ns)) <-> i.minusNanos(ns)
      }
    }
  ) @@ TestAspect.ignore
}
