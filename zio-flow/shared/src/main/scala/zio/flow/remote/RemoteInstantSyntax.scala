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
import zio.flow.remote.numeric.Numeric._
import zio._

import java.time.temporal.{ChronoField, ChronoUnit}

final class RemoteInstantSyntax(val self: Remote[Instant]) extends AnyVal {

  def get(unit: Remote[ChronoField]): Remote[Int] =
    unit.`match`(
      ChronoField.NANO_OF_SECOND  -> getNano,
      ChronoField.MICRO_OF_SECOND -> getNano / 1000,
      ChronoField.MILLI_OF_SECOND -> getNano / 1000000
    )(Remote.fail("Unsupported unit"))

  def getLong(unit: Remote[ChronoField]): Remote[Long] =
    unit.`match`(
      ChronoField.NANO_OF_SECOND  -> getNano.toLong,
      ChronoField.MICRO_OF_SECOND -> (getNano / 1000).toLong,
      ChronoField.MILLI_OF_SECOND -> (getNano / 1000000).toLong,
      ChronoField.INSTANT_SECONDS -> getEpochSecond
    )(Remote.fail("Unsupported unit"))

  def getEpochSecond: Remote[Long] =
    (Remote.Unary[Instant, (Long, Int)](self, UnaryOperators.Conversion(RemoteConversions.InstantToTuple)): Remote[(Long, Int)])._1
  def getNano: Remote[Int] =
    (Remote.Unary[Instant, (Long, Int)](self, UnaryOperators.Conversion(RemoteConversions.InstantToTuple)): Remote[(Long, Int)])._2

  def isAfter(that: Remote[Instant]): Remote[Boolean] =
    self.getEpochSecond > that.getEpochSecond

  def isBefore(that: Remote[Instant]): Remote[Boolean] =
    self.getEpochSecond < that.getEpochSecond

  def minus(duration: Remote[Duration]): Remote[Instant] =
    self.plus(duration.multipliedBy(-1L))

  def minus(amountToSubtract: Remote[Long], unit: Remote[ChronoUnit]): Remote[Instant] =
    self.minus(Remote.DurationFromAmount(amountToSubtract, unit))

  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(secondsToSubtract, Remote(ChronoUnit.SECONDS))

  def minusNanos(nanosecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(nanosecondsToSubtract, Remote(ChronoUnit.NANOS))

  def minusMillis(milliSecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(milliSecondsToSubtract, Remote(ChronoUnit.MILLIS))

  def plus(duration: Remote[Duration]): Remote[Instant] =
    plusImpl(duration.getSeconds, duration.getNano.toLong)

  def plus(amountToAdd: Remote[Long], unit: Remote[ChronoUnit]): Remote[Instant] =
    self.plus(Duration.of(amountToAdd, unit))

  private def plusImpl(secondsToAdd: Remote[Long], nanosToAdd: Remote[Long]): Remote[Instant] =
    Instant.ofEpochSecond(
      math.addExact(math.addExact(getEpochSecond, secondsToAdd), nanosToAdd / 1000000000L),
      getNano.toLong + (nanosToAdd % 1000000000L)
    )

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(secondsToAdd, Remote(ChronoUnit.SECONDS))

  def plusMillis(milliSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(milliSecondsToAdd, Remote(ChronoUnit.MILLIS))

  def plusNanos(nanoSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(nanoSecondsToAdd, Remote(ChronoUnit.NANOS))

  def toEpochMilli: Remote[Long] =
    (getEpochSecond < 0L && getNano > 0).ifThenElse(
      ifTrue = math.addExact(
        math.multiplyExact(getEpochSecond + 1L, 1000L),
        ((getNano / 1000000) - 1000).toLong
      ),
      ifFalse = math.addExact(
        math.multiplyExact(getEpochSecond, 1000L),
        (getNano / 1000000).toLong
      )
    )

  def truncatedTo(unit: Remote[ChronoUnit]): Remote[Instant] =
    (unit === ChronoUnit.NANOS).ifThenElse(
      ifTrue = self,
      ifFalse = {
        Remote.bind(unit.getDuration) { unitDur =>
          (unitDur.getSeconds > 86400L).ifThenElse(
            ifTrue = Remote.fail("Unit is too large to be used for truncation"),
            ifFalse = {
              Remote.bind(unitDur.toNanos) { dur =>
                ((Remote(86400000000000L) % dur) === 0L).ifThenElse(
                  ifTrue = Remote.bind(self.getEpochSecond % 86400L * 1000000000L + self.getNano.toLong) { nod =>
                    val result = math.floorDiv(nod, dur) * dur
                    self.plusNanos(result - nod)
                  },
                  ifFalse = Remote.fail("Unit must divide into a standard day without remainder")
                )
              }
            }
          )
        }
      }
    )

  def until(endExclusive: Remote[Instant], unit: Remote[ChronoUnit]): Remote[Long] =
    unit.`match`(
      ChronoUnit.NANOS     -> nanosUntil(endExclusive),
      ChronoUnit.MICROS    -> nanosUntil(endExclusive) / 1000L,
      ChronoUnit.MILLIS    -> math.subtractExact(endExclusive.toEpochMilli, toEpochMilli),
      ChronoUnit.SECONDS   -> secondsUntil(endExclusive),
      ChronoUnit.MINUTES   -> secondsUntil(endExclusive) / 60L,
      ChronoUnit.HOURS     -> secondsUntil(endExclusive) / 3600L,
      ChronoUnit.HALF_DAYS -> secondsUntil(endExclusive) / 43200L,
      ChronoUnit.DAYS      -> secondsUntil(endExclusive) / 86400L
    )(Remote.fail("Unsupported unit"))

  private def nanosUntil(end: Remote[Instant]): Remote[Long] =
    math.addExact(
      math.multiplyExact(math.subtractExact(end.getEpochSecond, getEpochSecond), 1000000000L),
      (end.getNano - getNano).toLong
    )

  private def secondsUntil(end: Remote[Instant]): Remote[Long] =
    Remote.bind(math.subtractExact(end.getEpochSecond, getEpochSecond)) { secsDiff =>
      Remote.bind((end.getNano - getNano).toLong) { nanosDiff =>
        (secsDiff > 0L && nanosDiff < 0L).ifThenElse(
          ifTrue = secsDiff - 1L,
          ifFalse = (secsDiff < 0L && nanosDiff > 0L).ifThenElse(
            ifTrue = secsDiff + 1L,
            ifFalse = secsDiff
          )
        )
      }
    }

  def `with`(field: Remote[ChronoField], value: Remote[Long]): Remote[Instant] =
    field.`match`(
      ChronoField.NANO_OF_SECOND  -> Instant.ofEpochSecond(getEpochSecond, value),
      ChronoField.MICRO_OF_SECOND -> Instant.ofEpochSecond(getEpochSecond, value * 1000L),
      ChronoField.MILLI_OF_SECOND -> Instant.ofEpochSecond(getEpochSecond, value * 1000000L),
      ChronoField.INSTANT_SECONDS -> Instant.ofEpochSecond(value, getNano.toLong)
    )(Remote.fail("Unsupported unit"))
}
