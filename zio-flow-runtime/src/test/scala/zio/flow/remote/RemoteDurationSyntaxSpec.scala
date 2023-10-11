/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.remote

import zio._
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.utils.TestGen
import zio.flow._
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.test.{Gen, TestResult, check}

import java.time.temporal.ChronoUnit

object RemoteDurationSyntaxSpec extends RemoteSpecBase {
  override def spec = suite("RemoteDurationSyntax")(
    test("+") {
      check(Gen.finiteDuration, Gen.finiteDuration) { case (d1, d2) =>
        (Remote(d1) + Remote(d2)) <-> (d1 + d2)
      }
    },
    test("*") {
      check(Gen.finiteDuration, Gen.double) { case (duration, factor) =>
        Remote(duration) * Remote(factor) <-> duration * factor
      }
    },
    test("abs") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).abs() <-> duration.abs()
      }
    },
    test("addTo") {
      check(Gen.finiteDuration, Gen.instant) { case (duration, instant) =>
        Remote(duration).addTo(Remote(instant)) <-> duration.addTo(instant).asInstanceOf[Instant]
      }
    },
    test("dividedBy") {
      check(Gen.finiteDuration, Gen.long) { case (duration, divisor) =>
        Remote(duration).dividedBy(Remote(divisor)) <-> duration.dividedBy(divisor)
      }
    },
    test("get") {
      check(Gen.finiteDuration) { duration =>
        (Remote(duration).get(Remote(ChronoUnit.SECONDS)) <-> duration.get(ChronoUnit.SECONDS)) &&
        (Remote(duration).get(Remote(ChronoUnit.NANOS)) <-> duration.get(ChronoUnit.NANOS))
      }
    },
    test("getSeconds") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).getSeconds <-> duration.getSeconds
      }
    },
    test("getNano") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).getNano <-> duration.getNano
      }
    },
    test("isNegative") {
      check(Gen.finiteDuration) { d =>
        Remote(d).isNegative <-> d.isNegative
      }
    },
    test("isZero") {
      check(Gen.finiteDuration) { d =>
        Remote(d).isZero <-> d.isZero
      }
    },
    test("minus") {
      check(Gen.finiteDuration, Gen.finiteDuration) { case (d1, d2) =>
        (Remote(d1) minus Remote(d2)) <-> (d1 minus d2)
      }
    },
    test("minusDays") {
      check(Gen.finiteDuration, TestGen.long) { case (d, l) =>
        (Remote(d) minusDays Remote(l)) <-> (d minusDays l)
      }
    },
    test("minusHours") {
      check(Gen.finiteDuration, TestGen.long) { case (d, l) =>
        (Remote(d) minusHours Remote(l)) <-> (d minusHours l)
      }
    },
    test("minusMinutes") {
      check(Gen.finiteDuration, TestGen.long) { case (d, l) =>
        (Remote(d) minusMinutes Remote(l)) <-> (d minusMinutes l)
      }
    },
    test("minusSeconds") {
      check(Gen.finiteDuration, TestGen.long) { case (d, l) =>
        (Remote(d) minusSeconds Remote(l)) <-> (d minusSeconds l)
      }
    },
    test("minusNanos") {
      check(Gen.finiteDuration, TestGen.long) { case (d, l) =>
        (Remote(d) minusNanos Remote(l)) <-> (d minusNanos l)
      }
    },
    test("minus amount of temporal units") {
      check(Gen.finiteDuration, TestGen.long) { (d, l) =>
        ZIO
          .collectAll(
            List(
              Remote(d).minus(l, ChronoUnit.DAYS) <-> d.minus(l, ChronoUnit.DAYS),
              Remote(d).minus(l, ChronoUnit.HOURS) <-> d.minus(l, ChronoUnit.HOURS),
              Remote(d).minus(l, ChronoUnit.MINUTES) <-> d.minus(l, ChronoUnit.MINUTES),
              Remote(d).minus(l, ChronoUnit.SECONDS) <-> d.minus(l, ChronoUnit.SECONDS),
              Remote(d).minus(l, ChronoUnit.MILLIS) <-> d.minus(l, ChronoUnit.MILLIS),
              Remote(d).minus(l, ChronoUnit.NANOS) <-> d.minus(l, ChronoUnit.NANOS)
            )
          )
          .map(rs => TestResult.allSuccesses(rs.head, rs.tail: _*))

      }
    },
    test("multipliedBy") {
      check(Gen.finiteDuration(-10.hours, 10.hours), Gen.long(-1000, 1000)) { case (duration, factor) =>
        Remote(duration).multipliedBy(Remote(factor)) <-> duration.multipliedBy(factor)
      }
    },
    test("negated") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).negated() <-> duration.negated()
      }
    },
    test("plus") {
      check(Gen.finiteDuration, Gen.finiteDuration) { case (d1, d2) =>
        (Remote(d1) plus Remote(d2)) <-> (d1 plus d2)
      }
    },
    test("plusNanos") {
      check(Gen.finiteDuration, TestGen.long) { (d, l) =>
        Remote(d).plusNanos(l) <-> d.plusNanos(l)
      }
    },
    test("plusSeconds") {
      check(Gen.finiteDuration, TestGen.long) { (d, l) =>
        Remote(d).plusSeconds(l) <-> d.plusSeconds(l)
      }
    },
    test("plusMinutes") {
      check(Gen.finiteDuration, TestGen.long) { (d, l) =>
        Remote(d).plusMinutes(l) <-> d.plusMinutes(l)
      }
    },
    test("plusHours") {
      check(Gen.finiteDuration, TestGen.long) { (d, l) =>
        Remote(d).plusHours(l) <-> d.plusHours(l)
      }
    },
    test("plusDays") {
      check(Gen.finiteDuration, TestGen.long) { (d, l) =>
        Remote(d).plusDays(l) <-> d.plusDays(l)
      }
    },
    test("plus amount of temporal units") {
      check(Gen.finiteDuration, TestGen.long) { (d, l) =>
        ZIO
          .collectAll(
            List(
              Remote(d).plus(l, ChronoUnit.DAYS) <-> d.plus(l, ChronoUnit.DAYS),
              Remote(d).plus(l, ChronoUnit.HOURS) <-> d.plus(l, ChronoUnit.HOURS),
              Remote(d).plus(l, ChronoUnit.MINUTES) <-> d.plus(l, ChronoUnit.MINUTES),
              Remote(d).plus(l, ChronoUnit.SECONDS) <-> d.plus(l, ChronoUnit.SECONDS),
              Remote(d).plus(l, ChronoUnit.MILLIS) <-> d.plus(l, ChronoUnit.MILLIS),
              Remote(d).plus(l, ChronoUnit.NANOS) <-> d.plus(l, ChronoUnit.NANOS)
            )
          )
          .map(rs => TestResult.allSuccesses(rs.head, rs.tail: _*))

      }
    },
    test("subtractFrom") {
      check(Gen.finiteDuration, Gen.instant) { case (duration, instant) =>
        Remote(duration).subtractFrom(Remote(instant)) <-> duration.subtractFrom(instant).asInstanceOf[Instant]
      }
    },
    test("toDays") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toDays <-> duration.toDays
      }
    },
    test("toDaysPart") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toDaysPart <-> (duration.getSeconds / 86400L)
      }
    },
    test("toHours") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toHours <-> duration.toHours
      }
    },
    test("toHoursPart") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toHoursPart <-> (duration.toHours % 24L).toInt
      }
    },
    test("toMillis") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toMillis <-> duration.toMillis
      }
    },
    test("toMillisPart") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toMillisPart <-> duration.getNano / 1000000
      }
    },
    test("toMinutes") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toMinutes <-> duration.toMinutes
      }
    },
    test("toMinutesPart") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toMinutesPart <-> (duration.toMinutes % 60L).toInt
      }
    },
    test("toNanos") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toNanos <-> duration.toNanos
      }
    },
    test("toNanosPart") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toNanosPart <-> duration.getNano
      }
    },
    test("toSeconds") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toSeconds <-> duration.getSeconds
      }
    },
    test("toSecondsPart") {
      check(Gen.finiteDuration) { duration =>
        Remote(duration).toSecondsPart <-> (duration.getSeconds % 60L).toInt
      }
    },
    test("withNanos") {
      check(Gen.finiteDuration, Gen.int(0, 999999999)) { case (duration, nanos) =>
        Remote(duration).withNanos(Remote(nanos)) <-> duration.withNanos(nanos)
      }
    },
    test("withSeconds") {
      check(Gen.finiteDuration, Gen.long) { case (duration, seconds) =>
        Remote(duration).withSeconds(Remote(seconds)) <-> duration.withSeconds(seconds)
      }
    }
  ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
}
