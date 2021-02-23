package zio.flow

trait ExprOption[+A] {
  def self: Expr[A]

  def fold[A1, B](forNone: Expr[B], f: Expr[A1] => Expr[B])(implicit ev: A <:< Option[A1]): Expr[B] =
    Expr.FoldOption(self.widen[Option[A1]], forNone, f)

  def some: Expr[Option[A]] = Expr.Some(self)

  def isSome[A1](implicit ev: A <:< Option[A1]): Expr[Boolean] = fold(Expr(false), (_: Expr[A1]) => Expr(true))

  def isNone[A1](implicit ev: A <:< Option[A1]): Expr[Boolean] = fold(Expr(true), (_: Expr[A1]) => Expr(false))
}
