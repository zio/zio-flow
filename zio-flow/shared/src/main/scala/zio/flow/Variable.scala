package zio.flow

trait Variable[A] { self =>
  def get: ZFlow[Any, Nothing, A] = modify(a => (a, a))

  def set(a: Remote[A]): ZFlow[Any, Nothing, Unit] =
    modify[Unit](_ => ((), a))

  def modify[B](f: Remote[A] => (Remote[B], Remote[A])): ZFlow[Any, Nothing, B] =
    ZFlow.Modify(self, (e: Remote[A]) => Remote.tuple2(f(e)))

  def updateAndGet(f: Remote[A] => Remote[A]): ZFlow[Any, Nothing, A] =
    modify { a =>
      val a2 = f(a)
      (a2, a2)
    }

  def update(f: Remote[A] => Remote[A]): ZFlow[Any, Nothing, Unit] = updateAndGet(f).unit
}
