package zio.flow.runtime.operation.http

import zio.Duration

final case class HttpOperationPolicy(
  maxParallelRequestCount: Int,
  hostOverride: Option[String],
  retryPolicies: List[HttpRetryPolicy],
  circuitBreakerPolicy: Option[RetryPolicy],
  timeout: Duration
)
