package zio.flow

trait RemoteEither[+A] {
  def self: Remote[A]

  final def handleEither[B, C, D](left: Remote[B] => Remote[D], right: Remote[C] => Remote[D])(implicit
    ev: A <:< Either[B, C]
  ): Remote[D] =
    Remote.FoldEither(self.widen[Either[B, C]], left, right)

  //TODO
  final def handleEitherM[R, E, B, C, D](left: Remote[B] => ZFlow[R, E, D], right: Remote[C] => ZFlow[R, E, D])(implicit
    ev: A <:< Either[B, C]
  ): ZFlow[R, E, D] = ZFlow.FoldEither(self.widen[Either[B, C]], left, right)

  final def toLeft: Remote[Either[A, Nothing]] = Remote.Either0(Left(self))

  final def merge[B](implicit ev: A <:< Either[B, B]): Remote[B] =
    Remote.FoldEither[B, B, B](self.widen[Either[B, B]], identity(_), identity(_))

  final def toRight: Remote[Either[Nothing, A]] = Remote.Either0(Right(self))
}

object RemoteEither {

  //TODO
  def collectAll[E, A](values: Remote[List[Either[E, A]]]): Remote[Either[E, List[A]]] = ???
}
