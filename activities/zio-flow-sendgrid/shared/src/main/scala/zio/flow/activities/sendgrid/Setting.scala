package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class Setting(enable: Boolean)

object Setting {
  def derivedSchema                    = DeriveSchema.gen[Setting]
  implicit val schema: Schema[Setting] = derivedSchema

  val (enable) = Remote.makeAccessors[Setting](derivedSchema)
}
