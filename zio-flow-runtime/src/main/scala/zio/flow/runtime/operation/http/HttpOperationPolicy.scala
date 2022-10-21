package zio.flow.runtime.operation.http

import zio.Duration

/**
 * Specifies the behavior for calling operations on a given host
 *
 * @param maxParallelRequestCount
 *   The maximum number of parallel requests to this host
 * @param hostOverride
 *   Overrides the host with a custom one
 * @param retryPolicies
 *   Retry policies for different failure conditions
 * @param circuitBreakerPolicy
 *   Circuit breaker reset policy. If none, no circuit breaker will be used
 * @param timeout
 *   Timeout for the requests
 */
final case class HttpOperationPolicy(
  maxParallelRequestCount: Int,
  hostOverride: Option[String],
  retryPolicies: List[HttpRetryPolicy],
  circuitBreakerPolicy: Option[RetryPolicy],
  timeout: Duration
)
