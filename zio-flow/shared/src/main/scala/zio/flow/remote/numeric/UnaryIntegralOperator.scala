package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryIntegralOperator

object UnaryIntegralOperator {
  case object BitwiseNeg extends UnaryIntegralOperator
  case object NegExact   extends UnaryIntegralOperator
  case object IncExact   extends UnaryIntegralOperator
  case object DecExact   extends UnaryIntegralOperator

  implicit val schema: Schema[UnaryIntegralOperator] = DeriveSchema.gen
}
