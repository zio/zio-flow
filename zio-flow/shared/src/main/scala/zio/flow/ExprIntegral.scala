package zio.flow

trait ExprIntegral[+A] {
  def self: Expr[A]

  final def +[A1 >: A](that: Expr[A1])(implicit numeric: Integral[A1]): Expr[A1] =
    Expr.AddIntegral(self.widen[A1], that, numeric)

  final def /[A1 >: A](that: Expr[A1])(implicit numeric: Integral[A1]): Expr[A1] =
    Expr.DivIntegral(self.widen[A1], that, numeric)
}
