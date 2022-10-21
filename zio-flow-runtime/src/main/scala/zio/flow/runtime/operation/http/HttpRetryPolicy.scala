package zio.flow.runtime.operation.http

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
