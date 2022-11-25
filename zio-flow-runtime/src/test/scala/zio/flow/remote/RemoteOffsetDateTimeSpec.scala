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

import zio.{Scope, ZLayer}
import zio.flow._
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

import java.time.ZoneOffset

object RemoteOffsetDateTimeSpec extends RemoteSpecBase {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteOffsetDateTimeSyntax")(
      test("ofInstant")(
        check(Gen.instant, Gen.zoneOffset) { (instant, offset) =>
          OffsetDateTime.ofInstant(Remote(instant), Remote(offset)) <-> java.time.OffsetDateTime
            .ofInstant(instant, offset)
        }
      ),
      test("of")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime.of(
            dt.getYear,
            dt.getMonthValue,
            dt.getDayOfMonth,
            dt.getHour,
            dt.getMinute,
            dt.getSecond,
            dt.getNano,
            dt.getOffset
          ) <-> dt
        )
      ),
      test("toInstant")(
        check(Gen.instant) { instant =>
          OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toInstant <-> instant
        }
      ),
      test("getYear")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getYear <-> dt.getYear
        )
      ),
      test("getMonthValue")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getMonthValue <-> dt.getMonthValue
        )
      ),
      test("getDayOfMonth")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getDayOfMonth <-> dt.getDayOfMonth
        )
      ),
      test("getHour")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getHour <-> dt.getHour
        )
      ),
      test("getMinute")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getMinute <-> dt.getMinute
        )
      ),
      test("getSecond")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getSecond <-> dt.getSecond
        )
      ),
      test("getNano")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getNano <-> dt.getNano
        )
      ),
      test("getOffset")(
        check(Gen.offsetDateTime)(dt =>
          OffsetDateTime
            .of(
              dt.getYear,
              dt.getMonthValue,
              dt.getDayOfMonth,
              dt.getHour,
              dt.getMinute,
              dt.getSecond,
              dt.getNano,
              dt.getOffset
            )
            .getOffset <-> dt.getOffset
        )
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
}
