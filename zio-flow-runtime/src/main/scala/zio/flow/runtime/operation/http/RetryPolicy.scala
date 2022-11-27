package zio.flow.runtime.operation.http

import zio.Config

/**
 * Retry policy for an operation
 *
 * @param failAfter
 *   When to stop trying
 * @param repeatWith
 *   What to do before retrying
 * @param jitter
 *   Enables jittering the time delays between retries
 */
final case class RetryPolicy(
  failAfter: RetryLimit,
  repeatWith: Repetition,
  jitter: Boolean
)

object RetryPolicy {
  val config: Config[RetryPolicy] =
    (
      RetryLimit.config.nested("fail-after") ++
        Repetition.config.nested("repetition") ++
        Config.boolean("jitter")
    ).map { case (failAfter, repeatWith, jitter) =>
      RetryPolicy(failAfter, repeatWith, jitter)
    }
}
