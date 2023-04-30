package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class ClickTracking(
  enable: Boolean,
  enable_text: Boolean
)

object ClickTracking {
  def derivedSchema                          = DeriveSchema.gen[ClickTracking]
  implicit val schema: Schema[ClickTracking] = derivedSchema

  val (enable, enable_text) = Remote.makeAccessors[ClickTracking](derivedSchema)
}
