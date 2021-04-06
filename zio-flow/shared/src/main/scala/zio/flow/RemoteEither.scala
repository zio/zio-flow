package zio.flow

import zio.flow.Remote.apply

trait RemoteEither[+A] {
  def self: Remote[A]

  final def handleEither[B, C, D](left: Remote[B] => Remote[D], right: Remote[C] => Remote[D])(implicit
    ev: A <:< Either[B, C]
  ): Remote[D] =
    Remote.FoldEither(self.widen[Either[B, C]], left, right)

  final def handleEitherM[R, E, B, C, D](left: Remote[B] => ZFlow[R, E, D], right: Remote[C] => ZFlow[R, E, D])(implicit
    ev: A <:< Either[B, C]
  ): ZFlow[R, E, D] = ZFlow.unwrap(handleEither(left.andThen(Remote(_)), right.andThen(Remote(_))))

  final def toLeft: Remote[Either[A, Nothing]] = Remote.Either0(Left(self))

  final def merge[B](implicit ev: A <:< Either[B, B]): Remote[B] =
    Remote.FoldEither[B, B, B](self.widen[Either[B, B]], identity(_), identity(_))

  final def toRight: Remote[Either[Nothing, A]] = Remote.Either0(Right(self))

  final def isLeft[B, C](implicit ev: A <:< Either[B, C]): Remote[Boolean] =
    self.handleEither((_: Remote[B]) => Remote(true), (_: Remote[C]) => Remote(false))

  final def isRight[B, C](implicit ev: A <:< Either[B, C]): Remote[Boolean] =
    self.handleEither((_: Remote[B]) => Remote(false), (_: Remote[C]) => Remote(true))
}

object RemoteEither {

  def collectAll[E, A](values: Remote[List[Either[E, A]]]): Remote[Either[E, List[A]]] = {
    def combine[E, A](
      eitherList: Remote[Either[E, List[A]]],
      either: Remote[Either[E, A]]
    ): Remote[Either[E, List[A]]]                                                                             =
      eitherList.handleEither[E, List[A], Either[E, List[A]]](
        (_: Remote[E]) => eitherList,
        (remoteList: Remote[List[A]]) => combine2(either, remoteList)
      )
    def combine2[E, A](either: Remote[Either[E, A]], remoteList: Remote[List[A]]): Remote[Either[E, List[A]]] =
      either.handleEither[E, A, Either[E, List[A]]](
        (e: Remote[E]) => Remote.Either0(Left(e)),
        (a: Remote[A]) => Remote.Either0(Right(Remote.Cons(remoteList, a)))
      )
    values.fold(Right(Nil): Remote[Either[E, List[A]]])((el: Remote[Either[E, List[A]]], e: Remote[Either[E, A]]) =>
      combine(el, e)
    )
  }
}
