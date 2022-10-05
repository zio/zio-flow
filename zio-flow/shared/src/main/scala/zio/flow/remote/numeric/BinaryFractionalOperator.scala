package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryFractionalOperator

object BinaryFractionalOperator {
  case object Pow           extends BinaryFractionalOperator
  case object ArcTan2       extends BinaryFractionalOperator
  case object Hypot         extends BinaryFractionalOperator
  case object Scalb         extends BinaryFractionalOperator
  case object CopySign      extends BinaryFractionalOperator
  case object NextAfter     extends BinaryFractionalOperator
  case object IEEEremainder extends BinaryFractionalOperator

  implicit val schema: Schema[BinaryFractionalOperator] = DeriveSchema.gen
}
