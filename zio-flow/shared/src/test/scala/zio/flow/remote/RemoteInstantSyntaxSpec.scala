/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
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

import zio.ZLayer
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.utils.TestGen
import zio.flow._
import zio.schema.Schema
import zio.test.{Gen, TestAspect, check}

import java.time.temporal.{ChronoField, ChronoUnit}

object RemoteInstantSyntaxSpec extends RemoteSpecBase {
  override def spec = suite("RemoteInstantSyntax")(
    test("get") {
      check(
        Gen.instant,
        Gen.oneOf(
          Gen.const(ChronoField.NANO_OF_SECOND),
          Gen.const(ChronoField.MICRO_OF_SECOND),
          Gen.const(ChronoField.MILLI_OF_SECOND)
        )
      ) { case (i, f) =>
        Remote(i).get(f) <-> i.get(f)
      }
    },
    test("getLong") {
      check(
        Gen.instant,
        Gen.oneOf(
          Gen.const(ChronoField.NANO_OF_SECOND),
          Gen.const(ChronoField.MICRO_OF_SECOND),
          Gen.const(ChronoField.MILLI_OF_SECOND),
          Gen.const(ChronoField.INSTANT_SECONDS)
        )
      ) { case (i, f) =>
        Remote(i).getLong(f) <-> i.getLong(f)
      }
    },
    test("getEpochSecond") {
      check(Gen.instant) { d =>
        Remote(d).getEpochSecond <-> d.getEpochSecond
      }
    },
    test("getNano") {
      check(Gen.instant) { i =>
        Remote(i).getNano <-> i.getNano
      }
    },
    test("isAfter") {
      check(Gen.instant, Gen.instant) { case (i1, i2) =>
        (Remote(i1) isAfter Remote(i2)) <-> (i1 isAfter i2)
      }
    },
    test("isBefore") {
      check(Gen.instant, Gen.instant) { case (i1, i2) =>
        (Remote(i1) isBefore Remote(i2)) <-> (i1 isBefore i2)
      }
    },
    test("minus") {
      check(Gen.instant, Gen.finiteDuration) { case (i, d2) =>
        (Remote(i) minus Remote(d2)) <-> (i minus d2)
      }
    },
    test("minusSeconds") {
      check(TestGen.instant, TestGen.long) { case (i, s) =>
        Remote(i).minusSeconds(Remote(s)) <-> i.minusSeconds(s)
      }
    },
    test("minusMills") {
      check(TestGen.instant, TestGen.long) { case (i, ms) =>
        Remote(i).minusMillis(Remote(ms)) <-> i.minusMillis(ms)
      }
    },
    test("minusNanos") {
      check(TestGen.instant, TestGen.long) { case (i, ns) =>
        Remote(i).minusNanos(Remote(ns)) <-> i.minusNanos(ns)
      }
    },
    test("plus") {
      check(Gen.instant, Gen.finiteDuration) { case (i, d2) =>
        (Remote(i) plus Remote(d2)) <-> (i plus d2)
      }
    },
    test("plusSeconds") {
      check(TestGen.instant, TestGen.long) { case (i, s) =>
        Remote(i).plusSeconds(Remote(s)) <-> i.plusSeconds(s)
      }
    },
    test("plusMills") {
      check(TestGen.instant, TestGen.long) { case (i, ms) =>
        Remote(i).plusMillis(Remote(ms)) <-> i.plusMillis(ms)
      }
    },
    test("plusNanos") {
      check(TestGen.instant, TestGen.long) { case (i, ns) =>
        Remote(i).plusNanos(Remote(ns)) <-> i.plusNanos(ns)
      }
    },
    test("toEpochMilli") {
      check(Gen.instant(java.time.Instant.EPOCH, java.time.Instant.ofEpochMilli(Long.MaxValue))) { i =>
        Remote(i).toEpochMilli <-> i.toEpochMilli
      }
    },
    test("truncatedTo") {
      check(Gen.instant, TestGen.chronoUnit) { case (i, u) =>
        Remote(i).truncatedTo(Remote(u)) <-> i.truncatedTo(u)
      }
    } @@ TestAspect.ifProp("java.version")(v => !v.startsWith("1.8")), // https://bugs.openjdk.org/browse/JDK-8134928
    test("until") {
      check(
        Gen.instant(java.time.Instant.EPOCH, java.time.Instant.ofEpochSecond(100000L)),
        Gen.instant(java.time.Instant.EPOCH, java.time.Instant.ofEpochSecond(100000L)),
        Gen.oneOf(
          Gen.const(ChronoUnit.NANOS),
          Gen.const(ChronoUnit.MICROS),
          Gen.const(ChronoUnit.MILLIS),
          Gen.const(ChronoUnit.SECONDS),
          Gen.const(ChronoUnit.MINUTES),
          Gen.const(ChronoUnit.HOURS),
          Gen.const(ChronoUnit.HALF_DAYS),
          Gen.const(ChronoUnit.DAYS)
        )
      ) { case (start, end, unit) =>
        Remote(start).until(end, unit) <-> start.until(end, unit)
      }
    },
    test("with") {
      check(
        Gen.instant,
        Gen.long(0, 1000),
        Gen.oneOf(
          Gen.const(ChronoField.NANO_OF_SECOND),
          Gen.const(ChronoField.MICRO_OF_SECOND),
          Gen.const(ChronoField.MILLI_OF_SECOND),
          Gen.const(ChronoField.INSTANT_SECONDS)
        )
      ) { case (i, v, f) =>
        Remote(i).`with`(f, v) <-> i.`with`(f, v)
      }
    }
  ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  implicit val chronoFieldSchema: Schema[ChronoField] =
    Schema[String].transform(ChronoField.valueOf, _.name())

  implicit val chronoUnitSchema: Schema[ChronoUnit] =
    Schema[String].transform(ChronoUnit.valueOf, _.name())
}
