package zio.flow.runtime.operation.http

import zio.Config

/**
 * Specifies a retry policy for a given HTTP operation failure
 *
 * @param condition
 *   The condition the failure must match
 * @param policy
 *   Retry policy for these failures
 * @param breakCircuit
 *   If true, these failures will be reported to the circuit breaker
 */
final case class HttpRetryPolicy(condition: HttpRetryCondition, policy: RetryPolicy, breakCircuit: Boolean)

object HttpRetryPolicy {
  val config: Config[HttpRetryPolicy] =
    (
      HttpRetryCondition.config.nested("condition") ++
        RetryPolicy.config.nested("retry-policy") ++
        Config.boolean("break-circuit")
    ).map { case (condition, policy, breakCircuit) =>
      HttpRetryPolicy(condition, policy, breakCircuit)
    }
}
