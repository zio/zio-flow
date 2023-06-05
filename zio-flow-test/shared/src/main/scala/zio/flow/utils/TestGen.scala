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

package zio.flow.utils

import zio.test.Gen

import java.time.Instant
import java.time.temporal.{ChronoField, ChronoUnit}

object TestGen {

  def long: Gen[Any, Long] =
    Gen.long(Int.MinValue.toLong, Int.MaxValue.toLong)

  def instant: Gen[Any, Instant] =
    Gen.instant(Instant.EPOCH, Instant.MAX)

  def chronoField: Gen[Any, ChronoField] =
    Gen.elements(
      ChronoField.SECOND_OF_DAY,
      ChronoField.MINUTE_OF_HOUR,
      ChronoField.HOUR_OF_DAY
    )

  def chronoUnit: Gen[Any, ChronoUnit] =
    Gen.elements(
      ChronoUnit.SECONDS,
      ChronoUnit.MILLIS,
      ChronoUnit.NANOS
    )

}
