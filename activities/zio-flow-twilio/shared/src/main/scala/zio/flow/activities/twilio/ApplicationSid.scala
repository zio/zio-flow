package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class ApplicationSid(value: String) extends AnyVal

object ApplicationSid {
  implicit val schema: Schema[ApplicationSid] = Schema[String].transform(ApplicationSid(_), _.value)

  val derivedSchema = DeriveSchema.gen[ApplicationSid]
  val (value)       = Remote.makeAccessors[ApplicationSid](derivedSchema)
}
