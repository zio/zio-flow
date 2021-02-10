package zio.flow

trait ExprNumeric[+A] { self: Expr[A] =>
  final def +[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.AddNumeric(self.widen[A1], that, numeric)

  final def /[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.DivNumeric(self.widen[A1], that, numeric)

  final def *[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.MulNumeric(self.widen[A1], that, numeric)

  final def -[A1 >: A](that: Expr[A1])(implicit numeric: Numeric[A1]): Expr[A1] =
    Expr.SubNumeric(self.widen[A1], that, numeric)

}
