package zio.flow

import java.time.{ Duration, Instant }

trait RemoteInstant[+A] {
  def self: Remote[A]

  def isAfter(that: Remote[Instant])(implicit ev: A <:< Instant): Remote[Boolean] = self.getEpochSec > that.getEpochSec

  def isBefore(that: Remote[Instant])(implicit ev: A <:< Instant): Remote[Boolean] = self.getEpochSec < that.getEpochSec

  def getEpochSec(implicit ev: A <:< Instant): Remote[Long] =
    Remote.InstantToLong(self.widen[Instant])

  def plusDuration(duration: Remote[Duration])(implicit ev: A <:< Instant): Remote[Instant] = {
    val longDuration = duration.toSeconds
    val epochSecond  = getEpochSec
    val total        = longDuration + epochSecond

    Remote.fromEpochSec(total)
  }
}
