package zio.flow

class RemoteOptionSyntax[A](val self: Remote[Option[A]]) {

  def handleOption[B](forNone: Remote[B], f: Remote[A] => Remote[B]): Remote[B] =
    Remote.FoldOption(self, forNone, f)

  def isSome: Remote[Boolean] =
    handleOption(Remote(false), _ => Remote(true))

  def isNone: Remote[Boolean] =
    handleOption(Remote(true), _ => Remote(false))

  final def isEmpty: Remote[Boolean] = ???

  final def isDefined: Remote[Boolean] = ???
  def knownSize: Remote[Int] = ???

  final def contains[A1 >: A](elem: A1): Remote[Boolean] = ???

  def orElse[B >: A](alternative: Remote[Option[B]]): Remote[Option[B]] = ???

  final def zip[A1 >: A, B](that: Remote[Option[B]]): Remote[Option[(A1, B)]] = ???
}
