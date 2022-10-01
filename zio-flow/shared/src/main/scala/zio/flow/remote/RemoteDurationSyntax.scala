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

import java.time.Instant
import java.time.temporal.ChronoUnit

final class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {

  def abs(): Remote[Duration] =
    ???

  def addTo(temporal: Remote[Instant]): Remote[Instant] =
    ???

  def dividedBy(divisor: Remote[Long]): Remote[Duration] =
    ???

  def get(unit: Remote[ChronoUnit]): Remote[Long] =
    ???

  def getSeconds: Remote[Long] = Remote.DurationToLongs(self.widen[Duration])._1
  def getNano: Remote[Long]    = Remote.DurationToLongs(self.widen[Duration])._2

  def isNegative: Remote[Boolean] = self.getSeconds < 0L

  def isZero: Remote[Boolean] = self.getSeconds === 0L && self.getNano === 0L

  def minus(that: Remote[Duration]): Remote[Duration] =
    Remote.DurationPlusDuration(self, that.multipliedBy(-1L))

  def minus(amountToSubtract: Remote[Long], temporalUnit: Remote[ChronoUnit]): Remote[Duration] =
    minus(Remote.DurationFromAmount(amountToSubtract, temporalUnit))

  def minusDays(daysToSubtract: Remote[Long]): Remote[Duration] = minus(Duration.ofDays(daysToSubtract))

  def minusHours(hoursToSubtract: Remote[Long]): Remote[Duration] = minus(Duration.ofHours(hoursToSubtract))

  def minusMinutes(minutesToSubtract: Remote[Long]): Remote[Duration] = minus(Duration.ofMinutes(minutesToSubtract))

  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Duration] = minus(Duration.ofSeconds(secondsToSubtract))

  def minusNanos(nanosToSubtract: Remote[Long]): Remote[Duration] = minus(Duration.ofNanos(nanosToSubtract))

  def multipliedBy(amount: Remote[Long]): Remote[Duration] = Remote.DurationMultipliedBy(self, amount)

  def negated(): Remote[Duration] =
    ???

  def plus(that: Remote[Duration]): Remote[Duration] =
    Remote.DurationPlusDuration(self, that)

  def plus(amountToAdd: Remote[Long], temporalUnit: Remote[ChronoUnit]): Remote[Duration] =
    plus(Remote.DurationFromAmount(amountToAdd, temporalUnit))

  def plusDays(daysToAdd: Remote[Long]): Remote[Duration]       = plus(Duration.ofDays(daysToAdd))
  def plusHours(hoursToAdd: Remote[Long]): Remote[Duration]     = plus(Duration.ofHours(hoursToAdd))
  def plusMinutes(minutesToAdd: Remote[Long]): Remote[Duration] = plus(Duration.ofMinutes(minutesToAdd))
  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Duration] = plus(Duration.ofSeconds(secondsToAdd))
  def plusNanos(nanoToAdd: Remote[Long]): Remote[Duration]      = plus(Duration.ofNanos(nanoToAdd))

  def subtractFrom(temporal: Remote[Instant]): Remote[Instant] =
    ???

  def toDays(): Remote[Long] =
    ???

  def toHours(): Remote[Long] =
    ???

  def toMillis(): Remote[Long] =
    ???

  def toMinutes(): Remote[Long] =
    ???

  def toNanos(): Remote[Long] =
    ???

  def withNanos(nanos: Remote[Int]): Remote[Duration] =
    ???

  def withSeconds(seconds: Remote[Long]): Remote[Duration] =
    ???
}
