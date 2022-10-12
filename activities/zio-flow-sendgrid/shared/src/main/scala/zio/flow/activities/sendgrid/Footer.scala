package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Footer(
  enable: Boolean,
  text: Option[String] = None,
  html: Option[String] = None
)

object Footer {
  implicit val schema = DeriveSchema.gen[Footer]

  val (enable, text, html) = Remote.makeAccessors[Footer]
}
