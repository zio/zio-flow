package zio.flow

trait RemoteEither[+A] {
  def self: Remote[A]

  final def either[B, C, D](left: Remote[B] => Remote[D], right: Remote[C] => Remote[D])(implicit
    ev: A <:< Either[B, C]
  ): Remote[D] =
    Remote.FoldEither(self.widen[Either[B, C]], left, right)

  final def left: Remote[Either[A, Nothing]] = Remote.Either0(Left(self))

  final def right: Remote[Either[Nothing, A]] = Remote.Either0(Right(self))
}
