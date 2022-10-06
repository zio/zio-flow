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

final class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {

  def +(other: Remote[Duration]): Remote[Duration] = {
    val sum = self.toNanos + other.toNanos
    (sum >= 0L).ifThenElse(
      Duration.ofNanos(sum),
      Remote(Duration.Infinity)
    )
  }

  def *(factor: Remote[Double]): Remote[Duration] =
    (factor <= 0.0).ifThenElse(
      Remote(Duration.Zero),
      (factor <= Remote(Long.MaxValue).toDouble / self.toNanos.toDouble).ifThenElse(
        Duration.ofNanos(math.round(self.toNanos.toDouble * factor).toLong),
        Remote(Duration.Infinity)
      )
    )

  def abs(): Remote[Duration] =
    self.isNegative.ifThenElse(
      self.negated(),
      self
    )

  def addTo(temporal: Remote[Instant]): Remote[Instant] =
    temporal.plus(self)

  def dividedBy(divisor: Remote[Long]): Remote[Duration] = {
    val secondNs  = Remote(1000000000L)
    val resultNs  = self.toNanos / divisor
    val resultS   = resultNs / secondNs
    val remainder = resultNs % secondNs
    Duration.ofSeconds(resultS, remainder)
  }

  def get(unit: Remote[ChronoUnit]): Remote[Long] =
    unit.`match`(
      ChronoUnit.SECONDS -> getSeconds,
      ChronoUnit.NANOS   -> getNano.toLong
    )(Remote.fail("Unsupported unit"))

  def getSeconds: Remote[Long] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.DurationToTuple))._1

  def getNano: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.DurationToTuple))._2

  def isNegative: Remote[Boolean] =
    self.getSeconds < 0L

  def isZero: Remote[Boolean] =
    self.getSeconds === 0L && self.getNano === 0

  def minus(that: Remote[Duration]): Remote[Duration] =
    Duration.ofSecondsBigDecimal((self.toBigDecimalSeconds - that.toBigDecimalSeconds))

  def minus(amountToSubtract: Remote[Long], temporalUnit: Remote[ChronoUnit]): Remote[Duration] =
    minus(Duration.of(amountToSubtract, temporalUnit))

  def minusDays(daysToSubtract: Remote[Long]): Remote[Duration] =
    minus(Duration.ofDays(daysToSubtract))

  def minusHours(hoursToSubtract: Remote[Long]): Remote[Duration] =
    minus(Duration.ofHours(hoursToSubtract))

  def minusMinutes(minutesToSubtract: Remote[Long]): Remote[Duration] =
    minus(Duration.ofMinutes(minutesToSubtract))

  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Duration] =
    minus(Duration.ofSeconds(secondsToSubtract))

  def minusNanos(nanosToSubtract: Remote[Long]): Remote[Duration] =
    minus(Duration.ofNanos(nanosToSubtract))

  def multipliedBy(amount: Remote[Long]): Remote[Duration] =
    Duration.ofSecondsBigDecimal((self.toBigDecimalSeconds * BigDecimal(amount)))

  def negated(): Remote[Duration] =
    self.multipliedBy(-1L)

  def plus(that: Remote[Duration]): Remote[Duration] =
    Duration.ofSecondsBigDecimal((self.toBigDecimalSeconds + that.toBigDecimalSeconds))

  def plus(amountToAdd: Remote[Long], temporalUnit: Remote[ChronoUnit]): Remote[Duration] =
    self.plus(Duration.of(amountToAdd, temporalUnit))

  def plusDays(daysToAdd: Remote[Long]): Remote[Duration] =
    plus(Duration.ofDays(daysToAdd))
  def plusHours(hoursToAdd: Remote[Long]): Remote[Duration] =
    plus(Duration.ofHours(hoursToAdd))
  def plusMinutes(minutesToAdd: Remote[Long]): Remote[Duration] =
    plus(Duration.ofMinutes(minutesToAdd))
  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Duration] =
    plus(Duration.ofSeconds(secondsToAdd))
  def plusNanos(nanoToAdd: Remote[Long]): Remote[Duration] =
    plus(Duration.ofNanos(nanoToAdd))

  def subtractFrom(temporal: Remote[Instant]): Remote[Instant] =
    temporal.minus(self)

  def toDays: Remote[Long] =
    self.getSeconds / 86400L

  def toHours: Remote[Long] =
    self.getSeconds / 3600L

  def toMillis: Remote[Long] =
    (self.getSeconds * 1000L) + (self.getNano / 1000000).toLong

  def toMinutes: Remote[Long] =
    self.getSeconds / 60L

  def toNanos: Remote[Long] =
    (self.getSeconds * 1000000000L) + self.getNano.toLong

  private[flow] def toBigDecimalSeconds: Remote[BigDecimal] =
    BigDecimal(self.getSeconds) + (BigDecimal(self.getNano) / BigDecimal(1000000000L))

  def toSeconds: Remote[Long] =
    self.getSeconds

  def toDaysPart: Remote[Long] =
    self.getSeconds / 86400L

  def toHoursPart: Remote[Int] =
    (self.toHours % 24L).toInt

  def toMinutesPart: Remote[Int] =
    (self.toMinutes % 60L).toInt

  def toSecondsPart: Remote[Int] =
    (self.getSeconds % 60L).toInt

  def toMillisPart: Remote[Int] =
    self.getNano / 1000000

  def toNanosPart: Remote[Int] =
    self.getNano

  def withNanos(nanos: Remote[Int]): Remote[Duration] =
    Duration.ofSeconds(self.getSeconds, nanos.toLong)

  def withSeconds(seconds: Remote[Long]): Remote[Duration] =
    Duration.ofSeconds(seconds, self.getNano.toLong)
}
