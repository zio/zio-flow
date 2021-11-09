package zio.flow.remote

import java.time.temporal.{ ChronoUnit, TemporalAmount, TemporalField, TemporalUnit }
import java.time.{ Clock, Duration, Instant }

class RemoteInstantSyntax(val self: Remote[Instant]) extends AnyVal {
  def isAfter(that: RemoteInstantSyntax): Remote[Boolean] = self.getEpochSec > that.getEpochSec

  def isBefore(that: RemoteInstantSyntax): Remote[Boolean] = self.getEpochSec < that.getEpochSec

  def getEpochSec: Remote[Long] =
    Remote.InstantToLong(self)

  def plusDuration(duration: Remote[Duration]): Remote[Instant] = {
    val longDuration = duration.toSeconds
    val epochSecond  = getEpochSec
    val total        = longDuration + epochSecond

    Remote.fromEpochSec(total)
  }

  def minusDuration(duration: Remote[Duration]): Remote[Instant] = {
    val longDuration = duration.toSeconds
    val epochSecond  = getEpochSec
    val total        = epochSecond - longDuration

    Remote.fromEpochSec(total)
  }

  def get(field: Remote[TemporalField]): Remote[Int] = Remote.TemporalFieldOfInstant(self, field)

  def plus(amountToAdd: Remote[TemporalAmount]): Remote[Instant] =
    self.plusDuration(Remote.DurationFromTemporalAmount(amountToAdd))

  def plus(amountToAdd: Remote[Long], unit: Remote[TemporalUnit]): Remote[Instant] =
    self.plusDuration(Remote.AmountToDuration(amountToAdd, unit))

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Instant] =
    Remote.fromEpochSec(self.getEpochSec + secondsToAdd)

  def plusMillis(milliSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(milliSecondsToAdd, ChronoUnit.MILLIS)

  def plusNanos(nanoSecondsToAdd: Remote[Long]): Remote[Instant] =
    self.plus(nanoSecondsToAdd, ChronoUnit.NANOS)

  def minus(amountToSubtract: Remote[TemporalAmount]): Remote[Instant] =
    self.minusDuration(Remote.DurationFromTemporalAmount(amountToSubtract))

  def minus(amountToSubtract: Remote[Long], unit: Remote[TemporalUnit]): Remote[Instant] =
    self.minusDuration(Remote.AmountToDuration(amountToSubtract, unit))

  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Instant] =
    Remote.fromEpochSec(self.getEpochSec - secondsToSubtract)

  def minusNanos(nanosecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(nanosecondsToSubtract, ChronoUnit.NANOS)

  def minusMillis(milliSecondsToSubtract: Remote[Long]): Remote[Instant] =
    self.minus(milliSecondsToSubtract, ChronoUnit.MILLIS)
}

object RemoteInstantSyntax {
  def now(): Remote[Instant] = Remote(Instant.now())

  def now(clock: Remote[Clock]): Remote[Instant] = ???

  def ofEpochSecond(second: Remote[Long]): Remote[Instant] = ???

  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] = ???

  def parse(charSeq: Remote[String]): Remote[Instant] = ???
}
