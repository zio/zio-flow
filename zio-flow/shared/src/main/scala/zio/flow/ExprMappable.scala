package zio.flow

trait ExprMappable[+A] {
  def self: Expr[A]

  def filter[F[_], A1](predicate: Expr[A1] => Expr[Boolean])(implicit ev: A <:< F[A1], impl: Mappable[F]): Expr[F[A1]] =
    impl.performFilter(self.widen[F[A1]], predicate)

  def map[F[_], A1, B](f: Expr[A1] => Expr[B])(implicit ev: A <:< F[A1], impl: Mappable[F]): Expr[F[B]] =
    impl.performMap(self.widen[F[A1]], f)

  def flatMap[F[_], A1, B](f: Expr[A1] => Expr[F[B]])(implicit ev: A <:< F[A1], impl: Mappable[F]): Expr[F[B]] =
    impl.performFlatmap(self.widen[F[A1]], f)
}
