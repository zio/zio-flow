package zio.flow

sealed trait Operation[-I, +E, +A]
object Operation {
  final case class Http[I, E, A](
    url: java.net.URI,
    method: String = "GET",
    contentType: String
  ) extends Operation[I, E, A]
  final case class SendEmail(
    server: String,
    port: Int
  ) extends Operation[EmailRequest, Throwable, Unit]
}

final case class EmailRequest(
  to: List[String],
  from: Option[String],
  cc: List[String],
  bcc: List[String],
  body: String
)
