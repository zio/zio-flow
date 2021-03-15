package zio.flow

import java.time.temporal._
import java.time.{ Duration, Instant }

trait RemoteInstant[+A] {
  def self: Remote[A]

  def getLong(temporalUnit: Remote[TemporalUnit])(implicit ev: A <:< Instant): Remote[Long] =
    Remote.InstantToLong(self.widen[Instant], temporalUnit)

  def isAfter(that: Remote[Instant])(implicit ev: A <:< Instant): Remote[Boolean] = self.getEpochSec > that.getEpochSec

  def isBefore(that: Remote[Instant])(implicit ev: A <:< Instant): Remote[Boolean] = self.getEpochSec < that.getEpochSec

  def getEpochSec(implicit ev: A <:< Instant): Remote[Long] =
    Remote.InstantToLong(self.widen[Instant], Remote(ChronoUnit.SECONDS))

  def plusDuration(duration: Remote[Duration])(implicit ev: A <:< Instant): Remote[Instant] = {
    val longDuration = duration.seconds
    val epochSecond  = getEpochSec
    val total        = longDuration + epochSecond

    Remote.fromEpochSec(total)
  }
}
