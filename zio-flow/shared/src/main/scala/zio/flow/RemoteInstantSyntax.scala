package zio.flow

import java.time.{ Duration, Instant }

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
}
