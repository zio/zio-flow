package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class EmailAddress(email: String, name: String)

object EmailAddress {
  implicit val schema = DeriveSchema.gen[EmailAddress]

  val (email, name) = Remote.makeAccessors[EmailAddress]
}
