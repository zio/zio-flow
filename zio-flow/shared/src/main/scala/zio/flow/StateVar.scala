package zio.flow

trait StateVar[A] { self =>
  def get: Workflow[Any, Nothing, Expr[A]] = modify(a => (a, a))

  def set(a: Expr[A]): Workflow[Any, Nothing, Expr[Unit]] =
    modify[Unit](_ => ((), a))

  def modify[B](f: Expr[A] => (Expr[B], Expr[A])): Workflow[Any, Nothing, Expr[B]] =
    Workflow.Modify(self, (e: Expr[A]) => Expr.tuple2(f(e)))

  def updateAndGet(f: Expr[A] => Expr[A]): Workflow[Any, Nothing, Expr[A]] =
    modify { a =>
      val a2 = f(a)
      (a2, a2)
    }

  def update(f: Expr[A] => Expr[A]): Workflow[Any, Nothing, Unit] = updateAndGet(f).unit
}