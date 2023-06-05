package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class Attachment(
  content: String,
  `type`: String,
  filename: String,
  disposition: String, // TODO: should be ContentDisposition enum (inline / attachment)
  content_id: Option[String] = None
)

object Attachment {
  def derivedSchema                       = DeriveSchema.gen[Attachment]
  implicit val schema: Schema[Attachment] = derivedSchema

  val (content, typ, filename, disposition, content_id) = Remote.makeAccessors[Attachment](derivedSchema)
}
