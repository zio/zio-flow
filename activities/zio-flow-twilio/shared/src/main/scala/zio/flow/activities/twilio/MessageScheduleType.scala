package zio.flow.activities.twilio

import zio.schema.DeriveSchema

sealed trait MessageScheduleType
object MessageScheduleType {
  case object fixed extends MessageScheduleType

  implicit val schema = DeriveSchema.gen[MessageScheduleType]
}
