package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryNumericOperator
object UnaryNumericOperator {
  case object Neg   extends UnaryNumericOperator
  case object Abs   extends UnaryNumericOperator
  case object Floor extends UnaryNumericOperator
  case object Ceil  extends UnaryNumericOperator
  case object Round extends UnaryNumericOperator

  implicit val schema: Schema[UnaryNumericOperator] = DeriveSchema.gen
}
