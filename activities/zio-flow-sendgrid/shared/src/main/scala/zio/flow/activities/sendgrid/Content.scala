package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class Content(`type`: String, value: String)

object Content {
  def derivedSchema                    = DeriveSchema.gen[Content]
  implicit val schema: Schema[Content] = derivedSchema

  val (typ, value) = Remote.makeAccessors[Content](derivedSchema)
}
