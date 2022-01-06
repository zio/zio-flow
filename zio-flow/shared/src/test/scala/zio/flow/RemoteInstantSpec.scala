package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.random.Random
import zio.test._

import java.time.Instant

object RemoteInstantSpec extends ZIOSpecDefault {
  override def spec = suite("RemoteInstantSpec")(
    test("isAfter") {
      check(Gen.instant, Gen.instant) { case (i1, i2) =>
        (Remote(i1) isAfter Remote(i2)) <-> (i1 isAfter i2)
      }
    },
    test("plusDuration") {
      check(Gen.instant, Gen.instant) { case (i1, i2) =>
        (Remote(i1) isBefore Remote(i2)) <-> (i1 isBefore i2)
      }
    },
    test("plusDuration") {
      check(Gen.instant, Gen.finiteDuration) { case (i, d2) =>
        (Remote(i) plusDuration Remote(d2)) <-> (i plus d2)
      }
    },
    test("getEpochSec") {
      check(Gen.instant) { d =>
        Remote(d).getEpochSec <-> d.getEpochSecond
      }
    },
    test("plusSeconds") {
      check(genInstant, genLong) { case (i, s) =>
        Remote(i).plusSeconds(Remote(s)) <-> i.plusSeconds(s)
      }
    },
    test("plusMills") {
      check(genInstant, genLong) { case (i, ms) =>
        Remote(i).plusMillis(Remote(ms)) <-> i.plusMillis(ms)
      }
    },
    test("plusNanos") {
      check(genInstantt, Gen.long) { case (i, ns) =>
        Remote(i).plusMillis(Remote(ns)) <-> i.plusNanos(ns)
      }
    },
    test("minusSeconds") {
      check(genInstant, genLong) { case (i, s) =>
        Remote(i).minusSeconds(Remote(s)) <-> i.minusSeconds(s)
      }
    },
    test("minusMills") {
      check(genInstant, genLong) { case (i, ms) =>
        Remote(i).minusMillis(Remote(ms)) <-> i.minusMillis(ms)
      }
    },
    test("minusNanos") {
      check(genInstant, genLong) { case (i, ns) =>
        Remote(i).minusNanos(Remote(ns)) <-> i.minusNanos(ns)
      }
    }
  )

  private def genLong: Gen[Random, Long] =
    Gen.long(Int.MinValue.toLong, Int.MaxValue.toLong)

  private def genInstant: Gen[Random, Instant] =
    Gen.instant(Instant.EPOCH, Instant.MAX)
}
