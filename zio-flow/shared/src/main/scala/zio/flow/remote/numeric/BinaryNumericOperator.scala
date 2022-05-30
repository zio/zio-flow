package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryNumericOperator
object BinaryNumericOperator {
  case object Add  extends BinaryNumericOperator
  case object Mul  extends BinaryNumericOperator
  case object Div  extends BinaryNumericOperator
  case object Mod  extends BinaryNumericOperator
  case object Pow  extends BinaryNumericOperator
  case object Root extends BinaryNumericOperator
  case object Log  extends BinaryNumericOperator
  case object Min  extends BinaryNumericOperator
  case object Max  extends BinaryNumericOperator

  implicit val schema: Schema[BinaryNumericOperator] = DeriveSchema.gen
}
