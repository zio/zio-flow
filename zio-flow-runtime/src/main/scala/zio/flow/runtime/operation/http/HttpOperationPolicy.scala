package zio.flow.runtime.operation.http

import zio.Duration
import zio.Config

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

object HttpOperationPolicy {
  val config: Config[HttpOperationPolicy] =
    (
      Config.int("max-parallel-request-count") ++
        Config.string("host-override").optional ++
        Config.listOf("retry-policies", HttpRetryPolicy.config).optional.map(_.getOrElse(List.empty)) ++
        RetryPolicy.config.nested("circuit-breaker-policy").optional ++
        Config.duration("timeout")
    ).map { case (maxParallelRequestCount, hostOverride, retryPolicies, circuitBreakerPolicy, timeout) =>
      HttpOperationPolicy(maxParallelRequestCount, hostOverride, retryPolicies, circuitBreakerPolicy, timeout)
    }
}
