package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Content(`type`: String, value: String)

object Content {
  implicit val schema = DeriveSchema.gen[Content]

  val (typ, value) = Remote.makeAccessors[Content]
}
