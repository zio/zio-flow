package zio.flow

class RemoteTuple2Syntax[A, B](val self: Remote[(A, B)]) {
  def _1: Remote[A] = Remote.First(self)
  def _2: Remote[B] = Remote.Second(self)
}

class RemoteTuple3Syntax[A, B, C](val self: Remote[(A, B, C)]) {
  def _1: Remote[A] = Remote.FirstOf3(self)
  def _2: Remote[B] = Remote.SecondOf3(self)
  def _3: Remote[C] = Remote.ThirdOf3(self)
}

class RemoteTuple4Syntax[A, B, C, D](val self: Remote[(A, B, C, D)]) {
  def _1: Remote[A] = Remote.FirstOf4(self)
  def _2: Remote[B] = Remote.SecondOf4(self)
  def _3: Remote[C] = Remote.ThirdOf4(self)
  def _4: Remote[D] = Remote.FourthOf4(self)
}

class RemoteTuple5Syntax[A, B, C, D, E](val self: Remote[(A, B, C, D, E)]) {
  def _1: Remote[A] = Remote.FirstOf5(self)
  def _2: Remote[B] = Remote.SecondOf5(self)
  def _3: Remote[C] = Remote.ThirdOf5(self)
  def _4: Remote[D] = Remote.FourthOf5(self)
  def _5: Remote[E] = Remote.FifthOf5(self)
}
