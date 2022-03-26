package zio.flow

import zio.schema.{DeriveSchema, Schema}

case class ActivityError(failure: String, details: Option[Throwable])
object ActivityError {
  implicit val schema: Schema[ActivityError] = DeriveSchema.gen
}
