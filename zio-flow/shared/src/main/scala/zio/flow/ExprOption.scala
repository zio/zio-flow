package zio.flow

trait ExprOption[A] {
  def self: Expr[A]

  def option[A1, B](forNone: Expr[B], f: Expr[A1] => Expr[B])(implicit ev: A <:< Option[A1]): Expr[B] =
    Expr.FoldOption(self.widen[Option[A1]], forNone, f)

  def some: Expr[Option[A]] = Expr.Some(self)

  def isSome[A1](implicit ev: A <:< Option[A1]): Expr[Boolean] = option(Expr(false), (_: Expr[A1]) => Expr(true))

  def isNone[A1](implicit ev: A <:< Option[A1]): Expr[Boolean] = option(Expr(true), (_: Expr[A1]) => Expr(false))

  def filter[A1](
    predicate: Expr[A1] => Expr[Boolean]
  )(implicit ev: A <:< Option[A1], impl: Mappable[Option]): Expr[Option[A1]] =
    impl.performFilter(self.widen[Option[A1]], predicate)

  def map[A1, B](f: Expr[A1] => Expr[B])(implicit ev: A <:< Option[A1], impl: Mappable[Option]): Expr[Option[B]] =
    impl.performMap(self.widen[Option[A1]], f)

  def flatMap[A1, B](
    f: Expr[A1] => Expr[Option[B]]
  )(implicit ev: A <:< Option[A1], impl: Mappable[Option]): Expr[Option[B]] =
    impl.performFlatmap(self.widen[Option[A1]], f)
}
