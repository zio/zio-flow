package zio.flow.activities.twilio

import zio.flow._
import zio.schema.DeriveSchema

final case class Message(sid: MessageSid)
// TODO: other fields

object Message {
  implicit val schema = DeriveSchema.gen[Message]

  val (sid) = Remote.makeAccessors[Message]
}
