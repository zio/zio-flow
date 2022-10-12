package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class OpenTracking(enable: Boolean, substitution_tag: Option[String] = None)

object OpenTracking {
  implicit val schema = DeriveSchema.gen[OpenTracking]

  val (enable, substitution_tag) = Remote.makeAccessors[OpenTracking]
}
