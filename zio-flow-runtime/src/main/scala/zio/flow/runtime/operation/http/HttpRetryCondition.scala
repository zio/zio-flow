package zio.flow.runtime.operation.http

sealed trait HttpRetryCondition

object HttpRetryCondition {
  case object Always                                                         extends HttpRetryCondition
  final case class ForSpecificStatus(status: Int)                            extends HttpRetryCondition
  case object For4xx                                                         extends HttpRetryCondition
  case object For5xx                                                         extends HttpRetryCondition
  case object OpenCircuitBreaker                                             extends HttpRetryCondition
  final case class Or(first: HttpRetryCondition, second: HttpRetryCondition) extends HttpRetryCondition
}
