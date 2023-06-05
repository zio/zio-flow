package zio.flow.runtime.operation.http

import zio.{Chunk, Config}

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

  lazy val config: Config[HttpRetryCondition] =
    Config.string.mapOrFail {
      case "always"               => Right(HttpRetryCondition.Always)
      case "for-4xx"              => Right(HttpRetryCondition.For4xx)
      case "for-5xx"              => Right(HttpRetryCondition.For5xx)
      case "open-circuit-breaker" => Right(HttpRetryCondition.OpenCircuitBreaker)
      case other                  => Left(Config.Error.InvalidData(Chunk.empty, s"Unknown HTTP retry condition: $other"))
    }.orElse {
      Config.int("for-specific-status").map(HttpRetryCondition.ForSpecificStatus.apply)
    }.orElse {
      (Config.defer(config).nested("first") ++
        Config.defer(config).nested("second")).nested("or").map { case (first, second) =>
        HttpRetryCondition.Or(first, second)
      }
    }
}
