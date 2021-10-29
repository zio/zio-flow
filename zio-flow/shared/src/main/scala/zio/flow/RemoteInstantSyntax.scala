package zio.flow

import java.time.temporal.{TemporalAmount, TemporalField, TemporalUnit}
import java.time.{Clock, Duration, Instant}

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

  def get(field : Remote[TemporalField]) : Remote[Int] = ???

  def plus (amountToAdd: Remote[TemporalAmount]) : Remote[Instant] = ???

  def plus(amountToAdd: Remote[Long], unit: Remote[TemporalUnit]) : Remote[Instant] = ???

  def plusSeconds (secondsToAdd : Remote[Long]) : Remote[Instant] = ???
  def plusMillis (milliSecondsToAdd : Remote[Long]) : Remote[Instant] = ???
  def plusNanos (nanoSecondsToAdd : Remote[Long]) : Remote[Instant] = ???

  def plus(secondsToAdd: Remote[Long], nanosToAdd: Remote[Long]) : Remote[Instant] = ???

  def minus(amountToSubtract: Remote[TemporalAmount]) : Remote[Instant] = ???

  def minus(amountToSubtract: Remote[Long], unit: Remote[TemporalUnit]): Remote[Instant] = ???

  def minusSeconds(secondsToSubtract: Remote[Long]) : Remote[Instant] = ???

  def minusNanos(nanosecondsToSubtract : Remote[Long]) : Remote[Instant] = ???

  def minusMillis(milliSecondsToSubtract : Remote[Long]) : Remote[Instant] = ???

}

object RemoteInstant {
  def now() : Remote[Instant] = ???

  def now (clock: Remote[Clock]): Remote[Instant] = ???

  def ofEpochSecond(second : Remote[Long]) : Remote[Instant] = ???

  def ofEpochMilli(milliSecond : Remote[Long]) : Remote[Instant] = ???

  def parse(charSeq : Remote[String]) : Remote[Instant] = ???
}
