package zio.flow

import java.time.temporal.{ TemporalAmount, TemporalField, TemporalUnit }
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

  def get(field: Remote[TemporalField]): Remote[Int] = Remote.fmap2(self, field)(_ get _)

  def plus(amountToAdd: Remote[TemporalAmount]): Remote[Instant] = Remote.fmap2(self, amountToAdd)(_ plus _)

  def plus(amountToAdd: Remote[Long], unit: Remote[TemporalUnit]): Remote[Instant] =
    Remote.fmap3(self, amountToAdd, unit)(_ plus (_, _))

  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Instant] = Remote.fmap2(self, secondsToAdd)(_ plusSeconds _)

  def plusMillis(milliSecondsToAdd: Remote[Long]): Remote[Instant] =
    Remote.fmap2(self, milliSecondsToAdd)(_ plusMillis _)

  def plusNanos(nanoSecondsToAdd: Remote[Long]): Remote[Instant] = Remote.fmap2(self, nanoSecondsToAdd)(_ plusNanos _)

  def minus(amountToSubtract: Remote[TemporalAmount]): Remote[Instant] =
    Remote.fmap2(self, amountToSubtract)(_ minus _)

  def minus(amountToSubtract: Remote[Long], unit: Remote[TemporalUnit]): Remote[Instant] =
    Remote.fmap3(self, amountToSubtract, unit)(_ minus (_, _)) // Remote.applyF2()

  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Instant] =
    Remote.fmap2(self, secondsToSubtract)(_ minusSeconds _)

  def minusNanos(nanosecondsToSubtract: Remote[Long]): Remote[Instant] =
    Remote.fmap2(self, nanosecondsToSubtract)(_ minusNanos _)

  def minusMillis(milliSecondsToSubtract: Remote[Long]): Remote[Instant] =
    Remote.fmap2(self, milliSecondsToSubtract)(_ minusMillis _)

}

object RemoteInstantSyntax {
  def now(): Remote[Instant] = Remote(Instant.now())

  def now(clock: Remote[Clock]): Remote[Instant] = Remote.fmap(clock)(Instant.now)

  def ofEpochSecond(second: Remote[Long]): Remote[Instant] = Remote.fmap(second)(Instant.ofEpochSecond)

  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] = Remote.fmap(milliSecond)(Instant.ofEpochMilli)

  def parse(charSeq: Remote[String]): Remote[Instant] = Remote.fmap(charSeq)(Instant.parse)
}
