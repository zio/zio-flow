package zio.flow

sealed trait Constructor[+A] { self =>
  final def flatMap[B](f: Remote[A] => Constructor[B]): Constructor[B] =
    Constructor.FlatMap(self, f)

  final def map[B](f: Remote[A] => Remote[B]): Constructor[B] =
    self.flatMap(a => Constructor.Return(f(a)))

  final def zip[B](that: Constructor[B]): Constructor[(A, B)] =
    self.flatMap(a => that.map(b => a -> b))
}

object Constructor {
  // TODO: Change Constructor => ZFlowState?????
  // TODO: Change `value` to be an `A` and the `Schema[A]`.
  final case class Return[A](value: Remote[A])                                          extends Constructor[A]
  final case class NewVar[A](name: String, defaultValue: Remote[A])                     extends Constructor[Variable[A]]
  final case class FlatMap[A, B](value: Constructor[A], k: Remote[A] => Constructor[B]) extends Constructor[B]

  def apply[A: Schema](a: A): Constructor[A] = Return(a)

  def newVar[A](name: String, value: Remote[A]): Constructor[Variable[A]] = NewVar(name, value)
}
