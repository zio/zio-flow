package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryNumericOperator {
  def show(a1: String, a2: String): String
}
object BinaryNumericOperator {
  case object Add  extends BinaryNumericOperator {
    def show(a1: String, a2: String) =
      a1 + "+" + a2
  }
  case object Mul  extends BinaryNumericOperator {
    def show(a1: String, a2: String) =
      a1 + "*" + a2
  }
  case object Div  extends BinaryNumericOperator {
    def show(a1: String, a2: String) =
      a1 + "/" + a2
  }
  case object Mod  extends BinaryNumericOperator {
    def show(a1: String, a2: String) =
      a1 + "%" + a2
  }
  case object Pow  extends BinaryNumericOperator {
    def show(a1: String, a2: String) =
      a1 + "^" + a2
  }
  case object Root extends BinaryNumericOperator{
    def show(a1: String, a2: String) =
      "root(" + a1 + "," + a2 + ")"
  }
  case object Log  extends BinaryNumericOperator{
    def show(a1: String, a2: String) =
      "log(" + a1 + "," + a2 + ")"
  }
  case object Min  extends BinaryNumericOperator{
    def show(a1: String, a2: String) =
      "min(" + a1 + "," + a2 + ")"
  }
  case object Max  extends BinaryNumericOperator{
    def show(a1: String, a2: String) =
      "max(" + a1 + "," + a2 + ")"
  }

  implicit val schema: Schema[BinaryNumericOperator] = DeriveSchema.gen
}
