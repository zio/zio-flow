package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class CallbackUrl(url: String)

object CallbackUrl {
  implicit val schema: Schema[CallbackUrl] = Schema[String].transform(CallbackUrl(_), _.url)

  val derivedSchema = DeriveSchema.gen[CallbackUrl]
  val (url)         = Remote.makeAccessors[CallbackUrl](derivedSchema)
}
