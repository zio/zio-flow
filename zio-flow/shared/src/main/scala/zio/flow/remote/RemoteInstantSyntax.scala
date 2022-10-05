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

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}

final class RemoteInstantSyntax(val self: Remote[Instant]) extends AnyVal {

  def isAfter(that: Remote[Instant]): Remote[Boolean]  = self.getEpochSecond > that.getEpochSecond
  def isBefore(that: Remote[Instant]): Remote[Boolean] = self.getEpochSecond < that.getEpochSecond

  def getEpochSecond: Remote[Long] = Remote.InstantToTuple(self.widen[Instant])._1
  def getNano: Remote[Int]         = Remote.InstantToTuple(self.widen[Instant])._2

  def truncatedTo(unit: Remote[ChronoUnit]): Remote[Instant] = Remote.InstantTruncate(self, unit)

  def plusDuration(duration: Remote[Duration]): Remote[Instant] =
    Remote.InstantPlusDuration(self, duration)

  def plus(amountToAdd: Remote[Long], unit: Remote[ChronoUnit]): Remote[Instant] =
    self.plusDuration(Remote.DurationFromAmount(amountToAdd, unit))

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(secondsToAdd, Remote(ChronoUnit.SECONDS))

  def plusMillis(milliSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(milliSecondsToAdd, Remote(ChronoUnit.MILLIS))

  def plusNanos(nanoSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(nanoSecondsToAdd, Remote(ChronoUnit.NANOS))

  def minusDuration(duration: Remote[Duration]): Remote[Instant] =
    self.plusDuration(duration.multipliedBy(-1L))

  def minus(amountToSubtract: Remote[Long], unit: Remote[ChronoUnit]): Remote[Instant] =
    self.minusDuration(Remote.DurationFromAmount(amountToSubtract, unit))

  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(secondsToSubtract, Remote(ChronoUnit.SECONDS))

  def minusNanos(nanosecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(nanosecondsToSubtract, Remote(ChronoUnit.NANOS))

  def minusMillis(milliSecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(milliSecondsToSubtract, Remote(ChronoUnit.MILLIS))
}

object RemoteInstant {
  def now: Remote[Instant]                            = Remote(Instant.now())
  def parse(charSeq: Remote[String]): Remote[Instant] = Remote.InstantFromString(charSeq)
}
