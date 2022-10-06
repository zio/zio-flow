package zio.flow.activities.twilio

import zio.schema.{DeriveSchema, Schema}

final case class Message(
  sid: MessageSid
  // TODO
)

object Message {
  implicit val schema: Schema[Message] = DeriveSchema.gen
}
