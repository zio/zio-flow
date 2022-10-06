package zio.flow.activities.twilio

import zio.schema.Schema

final case class PhoneNumber(value: String) extends AnyVal

object PhoneNumber {
  implicit val schema: Schema[PhoneNumber] = Schema[String].transform(PhoneNumber.apply, _.value)
}
