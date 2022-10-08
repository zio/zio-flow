package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class Seconds(value: Short) extends AnyVal

object Seconds {
  implicit val schema: Schema[Seconds] = Schema[Short].transform(Seconds(_), _.value)

  val derivedSchema = DeriveSchema.gen[Seconds]
  val (value)       = Remote.makeAccessors[Seconds](derivedSchema)
}
