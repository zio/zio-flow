package zio.flow.activities.twilio

import zio.schema.Schema

final case class Seconds(value: Short) extends AnyVal

object Seconds {
  implicit val schema: Schema[Seconds] = Schema[Short].transform(Seconds.apply, _.value)
}
