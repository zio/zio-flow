package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryIntegralOperator

object BinaryIntegralOperator {
  case object LeftShift          extends BinaryIntegralOperator
  case object RightShift         extends BinaryIntegralOperator
  case object UnsignedRightShift extends BinaryIntegralOperator
  case object And                extends BinaryIntegralOperator
  case object Or                 extends BinaryIntegralOperator
  case object Xor                extends BinaryIntegralOperator
  case object FloorDiv           extends BinaryIntegralOperator
  case object FloorMod           extends BinaryIntegralOperator
  case object AddExact           extends BinaryIntegralOperator
  case object SubExact           extends BinaryIntegralOperator
  case object MulExact           extends BinaryIntegralOperator

  implicit val schema: Schema[BinaryIntegralOperator] = DeriveSchema.gen
}
