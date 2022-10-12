package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Attachment(
  content: String,
  `type`: String,
  filename: String,
  disposition: String, // TODO: should be ContentDisposition enum (inline / attachment)
  content_id: Option[String] = None
)

object Attachment {
  implicit val schema = DeriveSchema.gen[Attachment]

  val (content, typ, filename, disposition, content_id) = Remote.makeAccessors[Attachment]
}
