package zio.flow.remote

import zio.flow.{ Mappable, Remote }

class RemoteMappableSyntax[A](self: Remote[A]) {

  def filter[F[_], A1](
    predicate: Remote[A1] => Remote[Boolean]
  )(implicit ev: A <:< F[A1], impl: Mappable[F]): Remote[F[A1]] =
    impl.performFilter(self.widen[F[A1]], predicate)

  def map[F[_], A1, B](f: Remote[A1] => Remote[B])(implicit ev: A <:< F[A1], impl: Mappable[F]): Remote[F[B]] =
    impl.performMap(self.widen[F[A1]], f)

  def flatMap[F[_], A1, B](f: Remote[A1] => Remote[F[B]])(implicit ev: A <:< F[A1], impl: Mappable[F]): Remote[F[B]] =
    impl.performFlatmap(self.widen[F[A1]], f)
}
