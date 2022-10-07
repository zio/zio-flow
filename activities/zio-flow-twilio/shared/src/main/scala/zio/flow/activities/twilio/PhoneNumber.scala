package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class PhoneNumber(value: String) extends AnyVal

object PhoneNumber {
  implicit val schema = DeriveSchema.gen[PhoneNumber]

  val (value) = Remote.makeAccessors[PhoneNumber]
}
