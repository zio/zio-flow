package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteDurationSpec extends ZIOSpecDefault {
  override def spec = suite("RemoteDurationSpec")(
    test("plusDuration2") {
      check(Gen.finiteDuration, Gen.finiteDuration) { case (d1, d2) =>
        (Remote(d1) plusDuration2 Remote(d2)) <-> (d1 plus d2)
      }
    },
    test("minusDuration") {
      check(Gen.finiteDuration, Gen.finiteDuration) { case (d1, d2) =>
        (Remote(d1) minusDuration Remote(d2)) <-> (d1 minus d2)
      }
    },
    test("getSeconds") {
      check(Gen.finiteDuration) { d =>
        Remote(d).getSeconds <-> d.getSeconds
      }
    },
    test("getNano") {
      check(Gen.finiteDuration) { d =>
        Remote(d).getNano <-> d.getNano.toLong
      }
    },
    test("isZero") {
      check(Gen.finiteDuration) { d =>
        Remote(d).isZero <-> d.isZero
      }
    },
    test("isNegative") {
      check(Gen.finiteDuration) { d =>
        Remote(d).isNegative <-> d.isNegative
      }
    },
    test("plusNanos") {
      check(Gen.finiteDuration, Gen.long) { (d, l) =>
        Remote(d).plusNanos(l) <-> d.plusNanos(l)
      }
    },
    test("plusSeconds") {
      check(Gen.finiteDuration, Gen.long) { (d, l) =>
        Remote(d).plusSeconds(l) <-> d.plusSeconds(l)
      }
    },
    test("plusMinutes") {
      check(Gen.finiteDuration, Gen.long) { (d, l) =>
        Remote(d).plusMinutes(l) <-> d.plusMinutes(l)
      }
    },
    test("plusHours") {
      check(Gen.finiteDuration, Gen.long) { (d, l) =>
        Remote(d).plusHours(l) <-> d.plusHours(l)
      }
    },
    test("plusDays") {
      check(Gen.finiteDuration, Gen.long) { (d, l) =>
        Remote(d).plusDays(l) <-> d.plusDays(l)
      }
    },
    test("toSeconds") {
      check(Gen.finiteDuration) { d =>
        Remote(d).toSeconds <-> d.getSeconds
      }
    }
  ) @@ TestAspect.ignore

}
