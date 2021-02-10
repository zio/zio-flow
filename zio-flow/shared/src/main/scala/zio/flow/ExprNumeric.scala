package zio.flow

trait ExprNumeric[+A] { self: Expr[A] =>
  final def +[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.AddNumeric(self.widen[A1], that, numeric)

  final def /[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.DivNumeric(self.widen[A1], that, numeric)

  final def *[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.MulNumeric(self.widen[A1], that, numeric)

  final def -[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.DiffNumeric(self.widen[A1], that, numeric)

  final def pow[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.PowNumeric(self.widen[A1], that, numeric)

  implicit val intZero: Expr[Int]       = Expr(0)
  implicit val shortZero: Expr[Short]   = Expr(0)
  implicit val longZero: Expr[Long]     = Expr(0L)
  implicit val floatZero: Expr[Float]   = Expr(0.0f)
  implicit val doubleZero: Expr[Double] = Expr(0.0)

  final def unary_-[A1 >: A](implicit numeric: Numeric[A1], zero: Expr[A1]): Expr[A1] =
    Expr.DiffNumeric(zero, self.widen[A1], numeric)
}
