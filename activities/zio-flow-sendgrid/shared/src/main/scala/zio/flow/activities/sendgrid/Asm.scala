package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Asm(group_id: Int, groups_to_display: List[Int])

object Asm {
  implicit val schema = DeriveSchema.gen[Asm]

  val (group_id, groups_to_display) = Remote.makeAccessors[Asm]
}
