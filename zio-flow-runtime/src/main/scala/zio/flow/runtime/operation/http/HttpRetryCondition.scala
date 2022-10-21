package zio.flow.runtime.operation.http

/** HTTP operation failure conditions */
sealed trait HttpRetryCondition

object HttpRetryCondition {

  /** Match all failures */
  case object Always extends HttpRetryCondition

  /** Match only a specific HTTP status code */
  final case class ForSpecificStatus(status: Int) extends HttpRetryCondition

  /** Match all HTTP 4xx status codes */
  case object For4xx extends HttpRetryCondition

  /** Match all HTTP 5xx status codes */
  case object For5xx extends HttpRetryCondition

  /** Matches the case when the circuit breaker is open */
  case object OpenCircuitBreaker extends HttpRetryCondition

  /** Combines two conditions */
  final case class Or(first: HttpRetryCondition, second: HttpRetryCondition) extends HttpRetryCondition
}
