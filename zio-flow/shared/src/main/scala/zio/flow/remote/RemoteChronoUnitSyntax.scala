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

import zio.Duration
import zio.flow._

import java.time.temporal.ChronoUnit

final class RemoteChronoUnitSyntax(val self: Remote[ChronoUnit]) extends AnyVal {

  def getDuration: Remote[Duration] =
    self.`match`(
      ChronoUnit.NANOS     -> Duration.ofNanos(1L),
      ChronoUnit.MICROS    -> Duration.ofNanos(1000L),
      ChronoUnit.MILLIS    -> Duration.ofNanos(1000000L),
      ChronoUnit.SECONDS   -> Duration.ofSeconds(1L),
      ChronoUnit.MINUTES   -> Duration.ofSeconds(60L),
      ChronoUnit.HOURS     -> Duration.ofSeconds(3600L),
      ChronoUnit.HALF_DAYS -> Duration.ofSeconds(43200L),
      ChronoUnit.DAYS      -> Duration.ofSeconds(86400L),
      ChronoUnit.WEEKS     -> Duration.ofSeconds(604800L),
      ChronoUnit.MONTHS    -> Duration.ofSeconds(2629746L),
      ChronoUnit.YEARS     -> Duration.ofSeconds(31556952L),
      ChronoUnit.DECADES   -> Duration.ofSeconds(315569520L),
      ChronoUnit.CENTURIES -> Duration.ofSeconds(3155695200L),
      ChronoUnit.MILLENNIA -> Duration.ofSeconds(31556952000L),
      ChronoUnit.ERAS      -> Duration.ofSeconds(31556952000000000L),
      ChronoUnit.FOREVER   -> Duration.ofSeconds(Long.MaxValue, 999999999L)
    )(Remote.fail("Unsupported ChronoUnit"))
}
