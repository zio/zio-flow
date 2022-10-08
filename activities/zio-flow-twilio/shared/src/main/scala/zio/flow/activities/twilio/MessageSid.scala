package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class MessageSid(value: String) extends AnyVal

object MessageSid {
  implicit val schema: Schema[MessageSid] = Schema[String].transform(MessageSid(_), _.value)

  val derivedSchema = DeriveSchema.gen[MessageSid]
  val (value)       = Remote.makeAccessors[MessageSid](derivedSchema)
}
