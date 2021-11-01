package zio.flow.remote

import java.time.Duration
import java.time.temporal.{ Temporal, TemporalAmount }

class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {

  def plusDuration2(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds + that.toSeconds)

  def minusDuration(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds - that.toSeconds)

  def durationToLong: Remote[Long] =
    Remote.DurationToLong(self.widen[Duration])

  def toSeconds: Remote[Long] = self.durationToLong

  def isZero: Remote[Boolean] = ???

  def isNegative: Remote[Boolean] = ???
  def getSeconds: Remote[Long]    = ???
  def getNano: Remote[Long]       = ???

  def plusDays(daysToAdd: Remote[Long]): Remote[Duration] = ???

  def plusHours(hoursToAdd: Remote[Long]): Remote[Duration] = ???

  def plusMinutes(minsToAdd: Remote[Long]): Remote[Duration] = ???

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Duration] = ???

  def plusNanos(nanoToAdd: Remote[Long]): Remote[Duration] = ???

}

object RemoteDuration {

  def from(amount: Remote[TemporalAmount]): Remote[Duration] = ???

  def parse(charSequence: Remote[String]): Remote[Duration] = ???

  def create(seconds: Remote[BigDecimal]): Remote[Duration] = ???

  def between(startInclusive: Remote[Temporal], endExclusive: Remote[Temporal]): Remote[Duration] = ???
}
