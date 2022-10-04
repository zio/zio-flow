package zio.flow.remote

import zio.flow.Remote

/**
 * Provides evidence that Remote[X] is either Remote[A] or Remote[B]
 */
trait RemoteTypeEither[X, A, B] {
  def fold[Y](
    a: Remote[A] => Remote[Y],
    b: Remote[B] => Remote[Y]
  )(value: Remote[X]): Remote[Y]
}

object RemoteTypeEither {

  implicit def isA[A, B]: RemoteTypeEither[A, A, B] = new RemoteTypeEither[A, A, B] {
    override def fold[Y](a: Remote[A] => Remote[Y], b: Remote[B] => Remote[Y])(value: Remote[A]): Remote[Y] =
      a(value)
  }

  implicit def isB[A, B]: RemoteTypeEither[B, A, B] = new RemoteTypeEither[B, A, B] {
    override def fold[Y](a: Remote[A] => Remote[Y], b: Remote[B] => Remote[Y])(value: Remote[B]): Remote[Y] =
      b(value)
  }
}
