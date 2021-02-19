package zio.flow

trait ExprBoolean[+A] {
  def self: Expr[A]

  import ExprBoolean._

  final def &&(that: Expr[Boolean])(implicit ev: A <:< Boolean): Expr[Boolean] =
    Expr.And(self.widen[Boolean], that)

  final def ||(that: Expr[Boolean])(implicit ev: A <:< Boolean): Expr[Boolean] =
    not(not(self.widen[Boolean]) && not(that))

  final def ifThenElse[B](ifTrue: => Expr[B], ifFalse: => Expr[B])(implicit ev: A <:< Boolean): Expr[B] =
    Expr.Branch(self.widen[Boolean], Expr.suspend(ifTrue), Expr.suspend(ifFalse))

  final def unary_!(implicit ev: A <:< Boolean): Expr[Boolean] =
    not(self.widen[Boolean])
}

private[zio] object ExprBoolean {
  final def not(expr: Expr[Boolean]): Expr[Boolean] =
    Expr.Not(expr)
}
