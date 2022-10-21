package zio.flow.operation.http

import zhttp.http.Status
import zio.flow.ActivityError

sealed trait HttpFailure {
  def toActivityError(method: HttpMethod, host: String): ActivityError
}

object HttpFailure {
  final case class ResponseBodyDecodeFailure(reason: String, body: String) extends HttpFailure {
    override def toActivityError(method: HttpMethod, host: String): ActivityError =
      ActivityError(s"$method request to $host failed to decode response body ($body): $reason", None)
  }
  final case class FailedToReceiveResponseBody(reason: Throwable) extends HttpFailure {
    override def toActivityError(method: HttpMethod, host: String): ActivityError =
      ActivityError(s"$method request to $host failed to receive response body", Some(reason))
  }
  final case class FailedToSendRequest(reason: Throwable) extends HttpFailure {
    override def toActivityError(method: HttpMethod, host: String): ActivityError =
      ActivityError(s"$method request to $host failed", Some(reason))
  }
  final case class Non200Response(status: Status) extends HttpFailure {
    override def toActivityError(method: HttpMethod, host: String): ActivityError =
      ActivityError(s"$method request to $host responded with ${status.code} $status", None)
  }
  case object CircuitBreakerOpen extends HttpFailure {
    override def toActivityError(method: HttpMethod, host: String): ActivityError =
      ActivityError(s"$method request to $host was canceled because circuit breaker is open", None)
  }
}
