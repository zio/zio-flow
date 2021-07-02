package zio.flow

import java.time.Duration

class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {

  def plusDuration2(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds + that.toSeconds)

  def minusDuration(that: Remote[Duration]): Remote[Duration] =
    Remote.ofSeconds(self.toSeconds - that.toSeconds)

  def durationToLong: Remote[Long] =
    Remote.DurationToLong(self.widen[Duration])

  def toSeconds: Remote[Long] = self.durationToLong
}
