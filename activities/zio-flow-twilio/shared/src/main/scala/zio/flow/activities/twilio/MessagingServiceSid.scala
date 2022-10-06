package zio.flow.activities.twilio

import zio.schema.Schema

final case class MessagingServiceSid(value: String) extends AnyVal

object MessagingServiceSid {
  implicit val schema: Schema[MessagingServiceSid] = Schema[String].transform(MessagingServiceSid.apply, _.value)
}
