package zio.flow

import java.time.Duration
import java.time.temporal.{ ChronoUnit, TemporalUnit }

class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {

  def plusDuration2(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds + that.toSeconds)

  def minusDuration(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds - that.toSeconds)

  def durationToLong(temporalUnit: Remote[TemporalUnit]): Remote[Long] =
    Remote.DurationToLong(self.widen[Duration], temporalUnit)

  def toSeconds: Remote[Long] = self.durationToLong(Remote(ChronoUnit.SECONDS))
}
