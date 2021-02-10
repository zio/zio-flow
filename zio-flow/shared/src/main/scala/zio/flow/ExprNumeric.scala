package zio.flow

trait ExprNumeric[+A] {
  def self: Expr[A]

  final def +[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.AddNumeric(self.widen[A1], that, numeric)

  final def /[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.DivNumeric(self.widen[A1], that, numeric)

  final def *[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.MulNumeric(self.widen[A1], that, numeric)

  final def -[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.AddNumeric(self.widen[A1], -that, numeric)

  final def pow[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.PowNumeric(self.widen[A1], that, numeric)

  final def unary_-[A1 >: A](implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.Negation(self.widen[A1], numeric)
}
