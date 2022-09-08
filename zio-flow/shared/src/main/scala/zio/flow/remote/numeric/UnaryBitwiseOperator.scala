package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryBitwiseOperator

object UnaryBitwiseOperator {
  case object BitwiseNeg extends UnaryBitwiseOperator

  implicit val schema: Schema[UnaryBitwiseOperator] = DeriveSchema.gen
}
