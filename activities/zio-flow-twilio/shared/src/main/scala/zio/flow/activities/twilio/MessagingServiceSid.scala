package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class MessagingServiceSid(value: String) extends AnyVal

object MessagingServiceSid {
  implicit val schema: Schema[MessagingServiceSid] = Schema[String].transform(MessagingServiceSid(_), _.value)

  val derivedSchema = DeriveSchema.gen[MessagingServiceSid]
  val (value)       = Remote.makeAccessors[MessagingServiceSid](derivedSchema)
}
