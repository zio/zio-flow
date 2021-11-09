package zio.flow

import zio.flow.remote.{ Remote, _ }
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
    testM("getSeconds") {
      check(Gen.anyFiniteDuration) { d =>
        Remote(d).getSeconds <-> d.getSeconds
      }
    },
    testM("getNano") {
      check(Gen.anyFiniteDuration) { d =>
        Remote(d).getNano <-> d.getNano.toLong
      }
    },
    testM("isZero") {
      check(Gen.anyFiniteDuration) { d =>
        Remote(d).isZero <-> d.isZero
      }
    },
    testM("isNegative") {
      check(Gen.anyFiniteDuration) { d =>
        Remote(d).isNegative <-> d.isNegative
      }
    },
    testM("plusNanos") {
      check(Gen.anyFiniteDuration, Gen.anyLong) { (d, l) =>
        Remote(d).plusNanos(l) <-> d.plusNanos(l)
      }
    },
    testM("plusSeconds") {
      check(Gen.anyFiniteDuration, Gen.anyLong) { (d, l) =>
        Remote(d).plusSeconds(l) <-> d.plusSeconds(l)
      }
    },
    testM("plusMinutes") {
      check(Gen.anyFiniteDuration, Gen.anyLong) { (d, l) =>
        Remote(d).plusMinutes(l) <-> d.plusMinutes(l)
      }
    },
    testM("plusHours") {
      check(Gen.anyFiniteDuration, Gen.anyLong) { (d, l) =>
        Remote(d).plusHours(l) <-> d.plusHours(l)
      }
    },
    testM("plusDays") {
      check(Gen.anyFiniteDuration, Gen.anyLong) { (d, l) =>
        Remote(d).plusDays(l) <-> d.plusDays(l)
      }
    },
    testM("toSeconds") {
      check(Gen.anyFiniteDuration) { d =>
        Remote(d).toSeconds <-> d.getSeconds
      }
    }
  ) @@ TestAspect.ignore

}
