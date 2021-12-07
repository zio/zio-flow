package zio.flow.remote

import zio.flow._

class RemoteVariableSyntax[A](val self: Remote[Variable[A]]) extends AnyVal {
  def get: ZFlow[Any, Nothing, A] = self.modify((a: Remote[A]) => (a, a))

  def set(a: Remote[A]): ZFlow[Any, Nothing, Unit] =
    self.modify((_: Remote[A]) => ((), a))

  def modify[B](f: Remote[A] => (Remote[B], Remote[A])): ZFlow[Any, Nothing, B] =
    ZFlow.Modify(self, (a: Remote[A]) => Remote.tuple2(f(a)))

  def updateAndGet(f: Remote[A] => Remote[A]): ZFlow[Any, Nothing, A] =
    self.modify { (a: Remote[A]) =>
      val a2 = f(a)
      (a2, a2)
    }

  def update(f: Remote[A] => Remote[A]): ZFlow[Any, Nothing, Unit] = updateAndGet(f).unit

  def waitUntil(predicate: Remote[A] => Remote[Boolean]): ZFlow[Any, Nothing, Any] = ZFlow.transaction { txn =>
    for {
      v <- self.get
      _ <- txn.retryUntil(predicate(v))
    } yield ()
  }
}
