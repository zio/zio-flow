package zio.flow

import scala.util.Try

class RemoteEitherSyntax[A, B](val self: Remote[Either[A, B]]) {

  final def handleEither[C](left: Remote[A] => Remote[C], right: Remote[B] => Remote[C]): Remote[C] =
    Remote.FoldEither(self, left, right)

  final def handleEitherM[R, E, C](
    left: Remote[A] => ZFlow[R, E, C],
    right: Remote[B] => ZFlow[R, E, C]
  ): ZFlow[R, E, C] = ZFlow.unwrap(handleEither(left.andThen(Remote(_)), right.andThen(Remote(_))))

  final def merge(implicit ev: Either[A, B] <:< Either[B, B]): Remote[B] =
    Remote.FoldEither[B, B, B](self.widen[Either[B, B]], identity(_), identity(_))

  final def isLeft: Remote[Boolean] =
    handleEither(_ => Remote(true), _ => Remote(false))

  final def isRight: Remote[Boolean] =
    handleEither(_ => Remote(false), _ => Remote(true))

  def swap: Remote[Either[B, A]] = Remote.SwapEither(self)

  def getOrElse(or: => Remote[B]): Remote[B] = ???

  def toOption: Remote[Option[B]] = handleEither(_ => Remote(None), Remote.Some0(_))

  def toTry(implicit ev: A <:< Throwable): Try[B] = ???
}

object RemoteEitherSyntax {

  def collectAll[E, A](values: Remote[List[Either[E, A]]]): Remote[Either[E, List[A]]] = {

    def combine[E, A](
      eitherList: RemoteEitherSyntax[E, List[A]],
      either: RemoteEitherSyntax[E, A]
    ): Remote[Either[E, List[A]]] =
      eitherList.handleEither(
        _ => eitherList.self,
        remoteList => combine2(either, remoteList).self
      )

    def combine2[A, B](either: RemoteEitherSyntax[A, B], remoteList: Remote[List[B]]): RemoteEitherSyntax[A, List[B]] =
      either.handleEither(
        a => Remote.Either0(Left(a)),
        b => Remote.Either0(Right(Remote.Cons(remoteList, b)))
      )

    values.fold(Right(Nil): Remote[Either[E, List[A]]])((el: Remote[Either[E, List[A]]], e: Remote[Either[E, A]]) =>
      combine(el, e)
    )
  }
}
