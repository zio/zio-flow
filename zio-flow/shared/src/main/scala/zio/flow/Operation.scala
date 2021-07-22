package zio.flow

import zio.schema.Schema

sealed trait Operation[-R, +A]
object Operation {
  final case class Http[R, A](
    url: java.net.URI,
    method: String = "GET",
    headers: Map[String, String],
    inputSchema: Schema[R],
    outputSchema: Schema[A]
  ) extends Operation[R, A]

  final case class SendEmail(
    server: String,
    port: Int
  ) extends Operation[EmailRequest, Unit]
}

final case class EmailRequest(
  to: List[String],
  from: Option[String],
  cc: List[String],
  bcc: List[String],
  body: String
)
