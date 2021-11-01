package zio.flow

import zio.flow.remote.Remote

sealed trait Mappable[F[_]] {
  def performMap[A, B](fa: Remote[F[A]], ab: Remote[A] => Remote[B]): Remote[F[B]]

  def performFilter[A](fa: Remote[F[A]], predicate: Remote[A] => Remote[Boolean]): Remote[F[A]]

  def performFlatmap[A, B](fa: Remote[F[A]], ab: Remote[A] => Remote[F[B]]): Remote[F[B]]
}

object Mappable {

  implicit case object MappableOption extends Mappable[Option] {
    override def performMap[A, B](fa: Remote[Option[A]], ab: Remote[A] => Remote[B]): Remote[Option[B]] =
      Remote.FoldOption(fa, Remote(None), (a: Remote[A]) => Remote.Some0(ab(a)))

    override def performFilter[A](fa: Remote[Option[A]], predicate: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
      Remote.FoldOption(fa, Remote(None), (a: Remote[A]) => predicate(a).ifThenElse(fa, Remote(None)))

    override def performFlatmap[A, B](fa: Remote[Option[A]], ab: Remote[A] => Remote[Option[B]]): Remote[Option[B]] =
      Remote.FoldOption(fa, Remote(None), ab)
  }

  implicit case object MappableList extends Mappable[List] {

    override def performMap[A, B](fa: Remote[List[A]], ab: Remote[A] => Remote[B]): Remote[List[B]] =
      Remote.Fold(fa, Remote(Nil), (tuple: Remote[(List[B], A)]) => Remote.Cons(tuple._1, ab(tuple._2)))

    override def performFilter[A](fa: Remote[List[A]], predicate: Remote[A] => Remote[Boolean]): Remote[List[A]] =
      Remote.Fold(
        fa,
        Remote(Nil),
        (tuple: Remote[(List[A], A)]) => predicate(tuple._2).ifThenElse(Remote.Cons(tuple._1, tuple._2), tuple._1)
      )

    override def performFlatmap[A, B](fa: Remote[List[A]], ab: Remote[A] => Remote[List[B]]): Remote[List[B]] =
      Remote.Fold(fa, Remote(Nil), (tuple: Remote[(List[B], A)]) => tuple._1 ++ ab(tuple._2))
  }

}
