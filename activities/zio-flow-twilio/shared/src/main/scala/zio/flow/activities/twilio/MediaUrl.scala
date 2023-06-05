package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class MediaUrl(url: String)

object MediaUrl {
  implicit val schema: Schema[MediaUrl] = Schema[String].transform(MediaUrl(_), _.url)

  val derivedSchema = DeriveSchema.gen[MediaUrl]
  val (url)         = Remote.makeAccessors[MediaUrl](derivedSchema)
}
