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

import zio.{ZIO, ZLayer}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.utils.TestGen
import zio.flow.{LocalContext, Remote, RemoteContext}
import zio.test.{Gen, TestResult, check}

import java.time.temporal.ChronoUnit

object RemoteDurationSpec extends RemoteSpecBase {
  override def spec = suite("RemoteDurationSpec")(
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
          .map(TestResult.all(_: _*))

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
          .map(TestResult.all(_: _*))

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
    }
  ).provideCustom(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
}
