package zio.flow

sealed trait Mappable[F[_]] {
  def performMap[A, B](fa: Expr[F[A]], ab: Expr[A] => Expr[B]): Expr[F[B]]

  def performFilter[A](fa: Expr[F[A]], predicate: Expr[A] => Expr[Boolean]): Expr[F[A]]

  def performFlatmap[A, B](fa: Expr[F[A]], ab: Expr[A] => Expr[F[B]]): Expr[F[B]]
}

object Mappable {

  implicit case object MappableOption extends Mappable[Option] {
    override def performMap[A, B](fa: Expr[Option[A]], ab: Expr[A] => Expr[B]): Expr[Option[B]] =
      Expr.FoldOption(fa, Expr(None), (a: Expr[A]) => Expr.Some(ab(a)))

    override def performFilter[A](fa: Expr[Option[A]], predicate: Expr[A] => Expr[Boolean]): Expr[Option[A]] =
      Expr.FoldOption(fa, Expr(None), (a: Expr[A]) => predicate(a).ifThenElse(fa, Expr(None)))

    override def performFlatmap[A, B](fa: Expr[Option[A]], ab: Expr[A] => Expr[Option[B]]): Expr[Option[B]] =
      Expr.FoldOption(fa, Expr(None), ab)
  }

  implicit case object MappableList extends Mappable[List] {

    override def performMap[A, B](fa: Expr[List[A]], ab: Expr[A] => Expr[B]): Expr[List[B]] =
      Expr.Fold(fa, Expr(Nil), (tuple: Expr[(List[B], A)]) => Expr.Cons(tuple._1, ab(tuple._2)))

    override def performFilter[A](fa: Expr[List[A]], predicate: Expr[A] => Expr[Boolean]): Expr[List[A]] =
      Expr.Fold(
        fa,
        Expr(Nil),
        (tuple: Expr[(List[A], A)]) => predicate(tuple._2).ifThenElse(Expr.Cons(tuple._1, tuple._2), tuple._1)
      )

    override def performFlatmap[A, B](fa: Expr[List[A]], ab: Expr[A] => Expr[List[B]]): Expr[List[B]] =
      Expr.Fold(fa, Expr(Nil), (tuple: Expr[(List[B], A)]) => tuple._1 ++ ab(tuple._2))
  }

}
