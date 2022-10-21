package zio.flow.runtime.operation.http

import zio.Duration

/** Specifies what's the limit of the retry policy */
sealed trait RetryLimit

object RetryLimit {

  /** Stop retrying after a given elapsed time */
  final case class ElapsedTime(duration: Duration) extends RetryLimit

  /** Stop retrying after a number of tries */
  final case class NumberOfRetries(count: Int) extends RetryLimit
}
