package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Setting(enable: Boolean)

object Setting {
  implicit val schema = DeriveSchema.gen[Setting]

  val (enable) = Remote.makeAccessors[Setting]
}
