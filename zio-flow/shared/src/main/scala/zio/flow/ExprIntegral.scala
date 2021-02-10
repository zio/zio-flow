package zio.flow

trait ExprIntegral[+A] {
  def self: Expr[A]

  final def +[A1 >: A](that: Expr[A1])(implicit numeric: Integral[A1]): Expr[A1] =
    Expr.AddIntegral(self.widen[A1], that, numeric)

  final def /[A1 >: A](that: Expr[A1])(implicit numeric: Integral[A1]): Expr[A1] =
    Expr.DivIntegral(self.widen[A1], that, numeric)

  final def *[A1 >: A](that: Expr[A1])(implicit numeric: Integral[A1]): Expr[A1] =
    Expr.MulIntegral(self.widen[A1], that, numeric)

  final def unary_-[A1 >: A](implicit numeric: Integral[A1]): Expr[A1] =
    Expr.Negation(self.widen[A], numeric)

  final def -[A1 >: A](that: Expr[A1])(implicit numeric: Integral[A1]): Expr[A1] =
    Expr.AddIntegral(self.widen[A1], -that, numeric)

  final def pow[A1 >: A](that: Expr[A1])(implicit numeric: Integral[A1]): Expr[A1] =
    Expr.PowIntegral(self.widen[A1], that, numeric)
}
