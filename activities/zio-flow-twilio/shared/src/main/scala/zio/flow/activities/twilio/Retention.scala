package zio.flow.activities.twilio

import zio.schema.DeriveSchema

sealed trait Retention
object Retention {
  case object retain extends Retention

  implicit val schema = DeriveSchema.gen[Retention]
}
