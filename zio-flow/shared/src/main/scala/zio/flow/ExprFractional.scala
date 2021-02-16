package zio.flow

trait ExprFractional[+A] {
  def self: Expr[A]

  final def sin[A1 >: A](implicit fractional: Fractional[A1]): Expr[A1] =
    Expr.SinFractional(self.widen[A1], fractional)

  final def cos[A1 >: A](implicit fractional: Fractional[A1]): Expr[A1] = {
    implicit val schemaA1 = fractional.schema
    Expr(fractional.fromDouble(1.0)) - (self.sin[A1] pow Expr(fractional.fromDouble(2.0)))
  }

  final def tan[A1 >: A](implicit fractional: Fractional[A1]): Expr[A1] =
    self.widen[A1].sin / (self.widen[A1].cos)

  final def sinInverse[A1 >: A](implicit fractional: Fractional[A1]): Expr[A1] =
    Expr.SinInverseFractional(self.widen[A1], fractional)

  final def cosInverse[A1 >: A](implicit fractional: Fractional[A1]): Expr[A1] = {
    implicit val schemaA1 = fractional.schema
    Expr(fractional.fromDouble(1.571)) - self.sinInverse[A1]
  }
}
