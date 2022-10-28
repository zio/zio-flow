package zio.flow.runtime.operation.http

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
