package zio.flow.runtime.operation.http

import zio.{Duration, ULayer, ZLayer}

/** Service to get a [[HttpOperationPolicy]] for a given host */
trait HttpOperationPolicies {
  def policyForHost(host: String): HttpOperationPolicy
}

object HttpOperationPolicies {
  val disabled: ULayer[HttpOperationPolicies] =
    ZLayer.succeed {
      new HttpOperationPolicies {
        override def policyForHost(host: String): HttpOperationPolicy =
          HttpOperationPolicy(
            maxParallelRequestCount = Int.MaxValue,
            hostOverride = None,
            retryPolicies = List.empty,
            circuitBreakerPolicy = None,
            timeout = Duration.Infinity
          )
      }
    }
}
