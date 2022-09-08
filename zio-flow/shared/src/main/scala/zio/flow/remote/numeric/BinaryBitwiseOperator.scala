package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryBitwiseOperator

object BinaryBitwiseOperator {
  case object LeftShift          extends BinaryBitwiseOperator
  case object RightShift         extends BinaryBitwiseOperator
  case object UnsignedRightShift extends BinaryBitwiseOperator
  case object And                extends BinaryBitwiseOperator
  case object Or                 extends BinaryBitwiseOperator
  case object Xor                extends BinaryBitwiseOperator

  implicit val schema: Schema[BinaryBitwiseOperator] = DeriveSchema.gen
}
