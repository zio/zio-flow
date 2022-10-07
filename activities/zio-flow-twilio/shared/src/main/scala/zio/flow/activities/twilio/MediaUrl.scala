package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class MediaUrl(url: String) extends AnyVal

object MediaUrl {
  implicit val schema = DeriveSchema.gen[MediaUrl]

  val (url) = Remote.makeAccessors[MediaUrl]
}
