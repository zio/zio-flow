package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryFractionalOperator {
  def show(a: String): String
}
object UnaryFractionalOperator {
  case object Sin    extends UnaryFractionalOperator {
    def show(a: String) =
      "sin(" + a + ")"
  }
  case object ArcSin extends UnaryFractionalOperator {
    def show(a: String) =
      "arcsin(" + a + ")"
  }
  case object ArcTan extends UnaryFractionalOperator {
    def show(a: String) =
      "arctan(" + a + ")"
  }

  implicit val schema: Schema[UnaryFractionalOperator] = DeriveSchema.gen
}
