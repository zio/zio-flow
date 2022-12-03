package zio.flow.runtime.operation.http

import zio.{Config, Duration, ULayer, ZIO, ZLayer}

/** Service to get a [[HttpOperationPolicy]] for a given host */
trait HttpOperationPolicies {
  def policyForHost(host: String): HttpOperationPolicy
}

object HttpOperationPolicies {
  private val disabledPolicy = HttpOperationPolicy(
    maxParallelRequestCount = Int.MaxValue,
    hostOverride = None,
    retryPolicies = List.empty,
    circuitBreakerPolicy = None,
    timeout = Duration.Infinity
  )

  val disabled: ULayer[HttpOperationPolicies] =
    ZLayer.succeed {
      new HttpOperationPolicies {
        override def policyForHost(host: String): HttpOperationPolicy =
          disabledPolicy
      }
    }

  def fromConfig(path: String*): ZLayer[Any, Config.Error, HttpOperationPolicies] =
    ZLayer {
      val config = path.reverse.foldLeft(
        HttpOperationPolicy.config.nested("default").optional ++
          Config
            .table("per-host", HttpOperationPolicy.config)
      )(_.nested(_))
      for {
        config            <- ZIO.config(config)
        (default, perHost) = config
        _                 <- ZIO.logDebug(s"Loaded default HTTP policy from config: $default")
        _                 <- ZIO.logDebug(s"Loaded per-host HTTP policies from config: $perHost")
      } yield new HttpOperationPolicies {
        override def policyForHost(host: String): HttpOperationPolicy =
          perHost.getOrElse(host, default.getOrElse(disabledPolicy))
      }
    }
}
