package zio.flow

import java.time.Duration
import java.time.temporal.{ ChronoUnit, TemporalUnit }

trait RemoteDuration[+A] {
  def self: Remote[A]

  def plus(that: Remote[Duration])(implicit ev: A <:< Duration): Remote[Duration] =
    Remote.ofSeconds(self.seconds + that.seconds)

  def minus(that: Remote[Duration])(implicit ev: A <:< Duration): Remote[Duration] =
    Remote.ofSeconds(self.seconds - that.seconds)

  def get(temporalUnit: Remote[TemporalUnit])(implicit ev: A <:< Duration): Remote[Long] =
    Remote.DurationToLong(self.widen[Duration], temporalUnit)

  def seconds(implicit ev: A <:< Duration): Remote[Long] = self.get(Remote(ChronoUnit.SECONDS))
}
