package zio.flow

sealed trait Constructor[+A] { self =>
  final def flatMap[B](f: Expr[A] => Constructor[B]): Constructor[B] =
    Constructor.FlatMap(self, f)

  final def map[B](f: Expr[A] => Expr[B]): Constructor[B] =
    self.flatMap(a => Constructor.Return(f(a)))

  final def zip[B](that: Constructor[B]): Constructor[(A, B)] =
    self.flatMap(a => that.map(b => a -> b))
}

object Constructor {
  final case class Return[A](value: Expr[A])                                          extends Constructor[A]
  final case class NewVar[A](defaultValue: Expr[A])                                   extends Constructor[StateVar[A]]
  final case class FlatMap[A, B](value: Constructor[A], k: Expr[A] => Constructor[B]) extends Constructor[B]

  def apply[A: Schema](a: A): Constructor[A] = Return(a)

  def newVar[A](value: Expr[A]): Constructor[StateVar[A]] = NewVar(value)
}
