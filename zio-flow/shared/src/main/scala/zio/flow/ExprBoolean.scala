package zio.flow

trait ExprBoolean[+A] { self: Expr[A] =>
  final def &&(that: Expr[Boolean])(implicit ev: A <:< Boolean): Expr[Boolean] =
    Expr.And(self.widen[Boolean], that)

  final def ||(that: Expr[Boolean])(implicit ev: A <:< Boolean): Expr[Boolean] =
    !(!self && !that)

  final def ifThenElse[B](ifTrue: Expr[B], ifFalse: Expr[B])(implicit ev: A <:< Boolean): Expr[B] =
    Expr.Branch(self.widen[Boolean], ifTrue, ifFalse)

  final def unary_!(implicit ev: A <:< Boolean): Expr[Boolean] =
    Expr.Not(self.widen[Boolean])
}
