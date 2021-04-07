package zio.flow

import java.time.Duration
import java.time.temporal.{ ChronoUnit, TemporalUnit }

trait RemoteDuration[+A] {
  def self: Remote[A]

  def plusDuration2(that: Remote[Duration])(implicit ev: A <:< Duration): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds + that.toSeconds)

  def minusDuration(that: Remote[Duration])(implicit ev: A <:< Duration): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds - that.toSeconds)

  def durationToLong(temporalUnit: Remote[TemporalUnit])(implicit ev: A <:< Duration): Remote[Long] =
    Remote.DurationToLong(self.widen[Duration], temporalUnit)

  def toSeconds(implicit ev: A <:< Duration): Remote[Long] = self.durationToLong(Remote(ChronoUnit.SECONDS))
}
