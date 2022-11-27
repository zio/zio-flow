package zio.flow.runtime.operation.http

import zio.{Config, Duration}

/** Specifies what's the limit of the retry policy */
sealed trait RetryLimit

object RetryLimit {

  /** Stop retrying after a given elapsed time */
  final case class ElapsedTime(duration: Duration) extends RetryLimit

  /** Stop retrying after a number of tries */
  final case class NumberOfRetries(count: Int) extends RetryLimit

  val config: Config[RetryLimit] =
    Config.duration("elapsed-time").map(ElapsedTime) orElse
      Config.int("number-of-retries").map(NumberOfRetries)
}
