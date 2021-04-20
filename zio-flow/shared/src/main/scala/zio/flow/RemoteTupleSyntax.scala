package zio.flow

class RemoteTuple2Syntax[A, B](val self: Remote[(A, B)]) {
  def _1: Remote[A] = Remote.First(self)
  def _2: Remote[B] = Remote.Second(self)
}

class RemoteTuple3Syntax[A, B, C](val self: Remote[(A, B, C)]) {
  def _1: Remote[A] = ???
  def _2: Remote[B] = ???
  def _3: Remote[C] = ???
}

class RemoteTuple4Syntax[A, B, C, D](val self: Remote[(A, B, C, D)]) {
  def _1: Remote[A] = ???
  def _2: Remote[B] = ???
  def _3: Remote[C] = ???
  def _4: Remote[D] = ???
}
