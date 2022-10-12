package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class PhoneNumber(value: String) extends AnyVal

object PhoneNumber {
  implicit val schema: Schema[PhoneNumber] = Schema[String].transform(PhoneNumber(_), _.value)

  val derivedSchema = DeriveSchema.gen[PhoneNumber]
  val (value)       = Remote.makeAccessors[PhoneNumber](derivedSchema)
}
