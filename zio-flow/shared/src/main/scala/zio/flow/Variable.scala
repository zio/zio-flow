package zio.flow

trait Variable[A] { self =>
  def get: ZFlow[Any, Nothing, A] = modify(a => (a, a))

  def set(a: Expr[A]): ZFlow[Any, Nothing, Unit] =
    modify[Unit](_ => ((), a))

  def modify[B](f: Expr[A] => (Expr[B], Expr[A])): ZFlow[Any, Nothing, B] =
    ZFlow.Modify(self, (e: Expr[A]) => Expr.tuple2(f(e)))

  def updateAndGet(f: Expr[A] => Expr[A]): ZFlow[Any, Nothing, A] =
    modify { a =>
      val a2 = f(a)
      (a2, a2)
    }

  def update(f: Expr[A] => Expr[A]): ZFlow[Any, Nothing, Unit] = updateAndGet(f).unit
}
