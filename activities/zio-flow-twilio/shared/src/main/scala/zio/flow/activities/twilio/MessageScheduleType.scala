package zio.flow.activities.twilio

import zio.schema.{DeriveSchema, Schema}

sealed trait MessageScheduleType
object MessageScheduleType {
  case object fixed extends MessageScheduleType

  def derivedSchema                                = DeriveSchema.gen[MessageScheduleType]
  implicit val schema: Schema[MessageScheduleType] = derivedSchema
}
