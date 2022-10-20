package zio.flow.runtime.operation.http

import zio.Duration

sealed trait RetryLimit

object RetryLimit {
  final case class ElapsedTime(duration: Duration) extends RetryLimit
  final case class NumberOfRetries(count: Int)     extends RetryLimit
}
