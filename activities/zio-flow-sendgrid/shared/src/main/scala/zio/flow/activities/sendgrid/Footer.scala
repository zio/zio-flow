package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class Footer(
  enable: Boolean,
  text: Option[String] = None,
  html: Option[String] = None
)

object Footer {
  def derivedSchema                   = DeriveSchema.gen[Footer]
  implicit val schema: Schema[Footer] = derivedSchema

  val (enable, text, html) = Remote.makeAccessors[Footer](derivedSchema)
}
