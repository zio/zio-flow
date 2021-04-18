package zio.flow

class RemoteOptionSyntax[A](val self: Remote[Option[A]]) {

  def handleOption[B](forNone: Remote[B], f: Remote[A] => Remote[B]): Remote[B] =
    Remote.FoldOption(self, forNone, f)

  def isSome: Remote[Boolean] =
    handleOption(Remote(false), _ => Remote(true))

  def isNone: Remote[Boolean] =
    handleOption(Remote(true), _ => Remote(false))
}
