package zio.flow.activities.twilio

import zio.schema.Schema

final case class MediaUrl(url: String) extends AnyVal

object MediaUrl {
  implicit val schema: Schema[MediaUrl] = Schema[String].transform(MediaUrl.apply, _.url)
}
