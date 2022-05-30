package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryFractionalOperator
object UnaryFractionalOperator {
  case object Sin    extends UnaryFractionalOperator
  case object ArcSin extends UnaryFractionalOperator
  case object ArcTan extends UnaryFractionalOperator

  implicit val schema: Schema[UnaryFractionalOperator] = DeriveSchema.gen
}
