package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryNumericOperator {
  def show(a: String): String
}
object UnaryNumericOperator {
  case object Neg   extends UnaryNumericOperator {
    def show(a: String) =
      "-(" + a + ")"
  }
  case object Abs   extends UnaryNumericOperator {
    def show(a: String) =
      "abs(" + a + ")"
  }
  case object Floor extends UnaryNumericOperator {
    def show(a: String) =
      "floor(" + a + ")"
  }
  case object Ceil  extends UnaryNumericOperator {
    def show(a: String) =
      "ceil(" + a + ")"
  }
  case object Round extends UnaryNumericOperator {
    def show(a: String) =
      "round(" + a + ")"
  }

  implicit val schema: Schema[UnaryNumericOperator] = DeriveSchema.gen
}
