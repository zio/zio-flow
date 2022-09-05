package zio.flow.server

import zio.schema.{DeriveSchema, Schema}

final case class ErrorResponse(message: String)
object ErrorResponse {
  implicit val schema: Schema[ErrorResponse] = DeriveSchema.gen
}
