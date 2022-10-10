package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class ClickTracking(
  enable: Boolean,
  enable_text: Boolean
)

object ClickTracking {
  implicit val schema = DeriveSchema.gen[ClickTracking]

  val (enable, enable_text) = Remote.makeAccessors[ClickTracking]
}
