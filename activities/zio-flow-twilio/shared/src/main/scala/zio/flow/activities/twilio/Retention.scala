package zio.flow.activities.twilio

import zio.schema.{DeriveSchema, Schema}

sealed trait Retention
object Retention {
  case object retain extends Retention

  def derivedSchema                      = DeriveSchema.gen[Retention]
  implicit val schema: Schema[Retention] = derivedSchema
}
