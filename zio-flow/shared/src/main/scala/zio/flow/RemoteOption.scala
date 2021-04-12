package zio.flow

trait RemoteOption[+A] {
  def self: Remote[A]

  def handleOption[A1, B](forNone: Remote[B], f: Remote[A1] => Remote[B])(implicit ev: A <:< Option[A1]): Remote[B] =
    Remote.FoldOption(self.widen[Option[A1]], forNone, f)

  def isSome[A1](implicit ev: A <:< Option[A1]): Remote[Boolean] =
    handleOption(Remote(false), (_: Remote[A1]) => Remote(true))

  def isNone[A1](implicit ev: A <:< Option[A1]): Remote[Boolean] =
    handleOption(Remote(true), (_: Remote[A1]) => Remote(false))
}
