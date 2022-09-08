package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryFractionalOperator

object BinaryFractionalOperator {
  case object Pow extends BinaryFractionalOperator

  implicit val schema: Schema[BinaryFractionalOperator] = DeriveSchema.gen
}
