package zio.flow

trait ExprOption[+A] {
  def self: Expr[A]

  def option[A1, B](forNone: Expr[B], f: Expr[A1] => Expr[B])(implicit ev: A <:< Option[A1]): Expr[B] =
    Expr.FoldOption(self.widen[Option[A1]], forNone, f)

  def some: Expr[Option[A]] = Expr.Some(self)

  def isSome[A1](implicit ev: A <:< Option[A1]): Expr[Boolean] = option(Expr(false), (_: Expr[A1]) => Expr(true))

  def isNone[A1](implicit ev: A <:< Option[A1]): Expr[Boolean] = option(Expr(true), (_: Expr[A1]) => Expr(false))

  def filter[A1](predicate: Expr[A1] => ExprBoolean[Boolean])(implicit ev: A <:< Option[A1]): Expr[Option[A]] =
    option(Expr(None), (a: Expr[A1]) => predicate(a).ifThenElse(some, Expr(None)))

  def map[A1, B](f: Expr[A1] => Expr[B])(implicit ev1: A <:< Option[A1]): Expr[Option[B]] =
    option(Expr(None), (a1: Expr[A1]) => Expr.Some(f(a1)))

  def flatMap[A1, B](f: Expr[A1] => Expr[Option[B]])(implicit ev: A <:< Option[A1]): Expr[Option[B]] =
    option(Expr(None), f)
}
