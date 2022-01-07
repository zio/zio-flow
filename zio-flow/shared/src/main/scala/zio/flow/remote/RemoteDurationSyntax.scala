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

import java.time.Duration
import java.time.temporal.{Temporal, TemporalAmount}

class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {

  def plusDuration(that: Remote[Duration]): Remote[Duration] =
    secsNanosToPlusDuration(self.toSecsNanos, that.toSecsNanos)

  def minusDuration(that: Remote[Duration]): Remote[Duration] =
    secsNanosToMinusDuration(self.toSecsNanos, that.toSecsNanos)

  def durationToLong: Remote[Long] =
    Remote.DurationToLong(self.widen[Duration])

  def toSeconds: Remote[Long] = self.durationToLong

  def toSecsNanos: Remote[(Long, Long)] =
    Remote.DurationToSecsNanos(self)

  def isZero: Remote[Boolean] = self.toSeconds === 0L && self.getNano === 0L

  def isNegative: Remote[Boolean] = self.toSeconds < 0L
  def getSeconds: Remote[Long]    = Remote.DurationToSecsNanos(self.widen[Duration])._1
  def getNano: Remote[Long]       = Remote.DurationToSecsNanos(self.widen[Duration])._2

  def plusDays(daysToAdd: Remote[Long]): Remote[Duration] = plusDuration(Remote.ofDays(daysToAdd))

  def plusHours(hoursToAdd: Remote[Long]): Remote[Duration] = plusDuration(Remote.ofHours(hoursToAdd))

  def plusMinutes(minsToAdd: Remote[Long]): Remote[Duration] = plusDuration(Remote.ofMinutes(minsToAdd))

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Duration] = plusDuration(Remote.ofSeconds(secondsToAdd))

  def plusNanos(nanoToAdd: Remote[Long]): Remote[Duration] = plusDuration(Remote.ofNanos(nanoToAdd))

  private def secsNanosToPlusDuration(left: Remote[(Long, Long)], right: Remote[(Long, Long)]): Remote[Duration] =
    Remote.SecsNanosToPlusDuration(left, right)

  private def secsNanosToMinusDuration(left: Remote[(Long, Long)], right: Remote[(Long, Long)]): Remote[Duration] =
    Remote.SecsNanosToMinusDuration(left, right)
}

object RemoteDuration {

  def from(amount: Remote[TemporalAmount]): Remote[Duration] = ???

  def parse(charSequence: Remote[String]): Remote[Duration] = ???

  def create(seconds: Remote[BigDecimal]): Remote[Duration] = ???

  def between(startInclusive: Remote[Temporal], endExclusive: Remote[Temporal]): Remote[Duration] = ???
}
