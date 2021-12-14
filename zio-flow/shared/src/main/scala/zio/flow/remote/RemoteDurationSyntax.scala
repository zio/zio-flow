package zio.flow.remote

import java.time.Duration
import java.time.temporal.{Temporal, TemporalAmount}

class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {

  def plusDuration2(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds + that.toSeconds)

  def minusDuration(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds - that.toSeconds)

  def durationToLong: Remote[Long] =
    Remote.DurationToLong(self.widen[Duration])

  def toSeconds: Remote[Long] = self.durationToLong

  def isZero: Remote[Boolean] = self.toSeconds === 0L && self.getNano === 0L

  def isNegative: Remote[Boolean] = self.toSeconds < 0L
  def getSeconds: Remote[Long]    = Remote.DurationToSecsNanos(self.widen[Duration])._1
  def getNano: Remote[Long]       = Remote.DurationToSecsNanos(self.widen[Duration])._2

  def plusDays(daysToAdd: Remote[Long]): Remote[Duration] = plusDuration2(Remote.ofDays(daysToAdd))

  def plusHours(hoursToAdd: Remote[Long]): Remote[Duration] = plusDuration2(Remote.ofHours(hoursToAdd))

  def plusMinutes(minsToAdd: Remote[Long]): Remote[Duration] = plusDuration2(Remote.ofMinutes(minsToAdd))

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Duration] = plusDuration2(Remote.ofSeconds(secondsToAdd))

  def plusNanos(nanoToAdd: Remote[Long]): Remote[Duration] = plusDuration2(Remote.ofNanos(nanoToAdd))

}

object RemoteDuration {

  def from(amount: Remote[TemporalAmount]): Remote[Duration] = ???

  def parse(charSequence: Remote[String]): Remote[Duration] = ???

  def create(seconds: Remote[BigDecimal]): Remote[Duration] = ???

  def between(startInclusive: Remote[Temporal], endExclusive: Remote[Temporal]): Remote[Duration] = ???
}
