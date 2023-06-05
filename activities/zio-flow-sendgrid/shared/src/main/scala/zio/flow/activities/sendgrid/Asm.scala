package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class Asm(group_id: Int, groups_to_display: List[Int])

object Asm {
  def derivedSchema                = DeriveSchema.gen[Asm]
  implicit val schema: Schema[Asm] = derivedSchema

  val (group_id, groups_to_display) = Remote.makeAccessors[Asm](derivedSchema)
}
