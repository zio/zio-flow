package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class AccountSid(value: String)

object AccountSid {
  implicit val schema: Schema[AccountSid] = Schema[String].transform(AccountSid(_), _.value)

  val derivedSchema = DeriveSchema.gen[AccountSid]
  val (value)       = Remote.makeAccessors[AccountSid](derivedSchema)
}
