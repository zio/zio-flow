package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Seconds(value: Short) extends AnyVal

object Seconds {
  implicit val schema = DeriveSchema.gen[Seconds]

  val (value) = Remote.makeAccessors[Seconds]
}
