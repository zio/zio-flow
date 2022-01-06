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

import zio.flow._

import java.time.temporal.{ChronoUnit, TemporalAmount, TemporalField, TemporalUnit}
import java.time.{Clock, Duration, Instant}

class RemoteInstantSyntax(val self: Remote[Instant]) extends AnyVal {
  def isAfter(that: RemoteInstantSyntax): Remote[Boolean] = self.getEpochSec > that.getEpochSec

  def isBefore(that: RemoteInstantSyntax): Remote[Boolean] = self.getEpochSec < that.getEpochSec

  def getEpochSec: Remote[Long] =
    Remote.InstantToLong(self)

  def toEpochSecsNanos: Remote[(Long, Int)] =
    Remote.InstantToTuple(self)

  def plusDuration(duration: Remote[Duration]): Remote[Instant] = {
    val durationSecsNanos = duration.toSecsNanos
    val instantEpochSecsNanos = toEpochSecsNanos

    Remote.ToInstantPlusDuration(instantEpochSecsNanos, durationSecsNanos)
  }

  def minusDuration(duration: Remote[Duration]): Remote[Instant] = {
    val durationSecsNanos = duration.toSecsNanos
    val instantEpochSecsNanos = toEpochSecsNanos

    Remote.ToInstantMinusDuration(instantEpochSecsNanos, durationSecsNanos)
  }

  def get(field: Remote[TemporalField]): Remote[Int] = Remote.TemporalFieldOfInstant(self, field)

  def plus(amountToAdd: Remote[TemporalAmount]): Remote[Instant] =
    self.plusDuration(Remote.DurationFromTemporalAmount(amountToAdd))

  def plus(amountToAdd: Remote[Long], unit: Remote[TemporalUnit]): Remote[Instant] =
    self.plusDuration(Remote.AmountToDuration(amountToAdd, unit))

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Instant] =
    self.plusDuration(Remote.AmountToDuration(secondsToAdd, Remote(ChronoUnit.SECONDS)))

  def plusMillis(milliSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plusDuration(Remote.AmountToDuration(milliSecondsToAdd, Remote(ChronoUnit.MILLIS)))

  def plusNanos(nanoSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plusDuration(Remote.AmountToDuration(nanoSecondsToAdd, Remote(ChronoUnit.NANOS)))

  def minus(amountToSubtract: Remote[TemporalAmount]): Remote[Instant] =
    self.minusDuration(Remote.DurationFromTemporalAmount(amountToSubtract))

  def minus(amountToSubtract: Remote[Long], unit: Remote[TemporalUnit]): Remote[Instant] =
    self.minusDuration(Remote.AmountToDuration(amountToSubtract, unit))

  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minusDuration(Remote.AmountToDuration(secondsToSubtract, Remote(ChronoUnit.SECONDS)))

  def minusNanos(nanosecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minusDuration(Remote.AmountToDuration(nanosecondsToSubtract, Remote(ChronoUnit.NANOS)))

  def minusMillis(milliSecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minusDuration(Remote.AmountToDuration(milliSecondsToSubtract, Remote(ChronoUnit.MILLIS)))
}

object RemoteInstantSyntax {
  def now(): Remote[Instant] = Remote(Instant.now())

  def now(clock: Remote[Clock]): Remote[Instant] = ???

  def ofEpochSecond(second: Remote[Long]): Remote[Instant] = ???

  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] = ???

  def parse(charSeq: Remote[String]): Remote[Instant] = ???
}
