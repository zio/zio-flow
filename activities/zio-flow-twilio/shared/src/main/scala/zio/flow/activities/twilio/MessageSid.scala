package zio.flow.activities.twilio

import zio.schema.Schema

final case class MessageSid(value: String) extends AnyVal

object MessageSid {
  implicit val schema: Schema[MessageSid] = Schema[String].transform(MessageSid.apply, _.value)
}
