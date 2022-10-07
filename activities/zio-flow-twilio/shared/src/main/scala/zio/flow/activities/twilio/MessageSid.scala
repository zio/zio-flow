package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class MessageSid(value: String) extends AnyVal

object MessageSid {
  implicit val schema = DeriveSchema.gen[MessageSid]

  val (value) = Remote.makeAccessors[MessageSid]
}
