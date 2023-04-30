package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class OpenTracking(enable: Boolean, substitution_tag: Option[String] = None)

object OpenTracking {
  def derivedSchema = DeriveSchema.gen[OpenTracking]

  implicit val schema: Schema[OpenTracking] = derivedSchema

  val (enable, substitution_tag) = Remote.makeAccessors[OpenTracking](derivedSchema)
}
