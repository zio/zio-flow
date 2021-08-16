package zio.flow

class RemoteOptionSyntax[A](val self: Remote[Option[A]]) {

  def handleOption[B](forNone: Remote[B], f: Remote[A] => Remote[B]): Remote[B] =
    Remote.FoldOption(self, forNone, f)

  def isNone: Remote[Boolean] =
    handleOption(Remote(true), _ => Remote(false))

  def isSome: Remote[Boolean] =
    handleOption(Remote(false), _ => Remote(true))

  def zip[A1 >: A, B](that: Remote[Option[B]]): Remote[Option[(A1, B)]] =
    handleOption(None, a => that.handleOption(None, b => Remote.Some0(Remote.tuple2((a, b)))))
}
