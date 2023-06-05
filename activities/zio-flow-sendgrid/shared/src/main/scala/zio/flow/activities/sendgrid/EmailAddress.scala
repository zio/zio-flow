package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class EmailAddress(email: String, name: String)

object EmailAddress {
  def derivedSchema                         = DeriveSchema.gen[EmailAddress]
  implicit val schema: Schema[EmailAddress] = derivedSchema

  val (email, name) = Remote.makeAccessors[EmailAddress](derivedSchema)
}
