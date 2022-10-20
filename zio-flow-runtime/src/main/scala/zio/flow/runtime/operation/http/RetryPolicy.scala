package zio.flow.runtime.operation.http

final case class RetryPolicy(
  failAfter: RetryLimit,
  repeatWith: Repetition,
  jitter: Boolean
)
