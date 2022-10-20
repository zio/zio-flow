package zio.flow.runtime.operation.http

final case class HttpRetryPolicy(condition: HttpRetryCondition, policy: RetryPolicy, breakCircuit: Boolean)
