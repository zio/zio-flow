package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class MessagingServiceSid(value: String) extends AnyVal

object MessagingServiceSid {
  implicit val schema = DeriveSchema.gen[MessagingServiceSid]

  val (value) = Remote.makeAccessors[MessagingServiceSid]
}
