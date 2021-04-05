package zio.flow

trait RemoteBoolean[+A] {
  def self: Remote[A]

  import RemoteBoolean._

  final def &&(that: Remote[Boolean])(implicit ev: A <:< Boolean): Remote[Boolean] =
    Remote.And(self.widen[Boolean], that)

  final def ||(that: Remote[Boolean])(implicit ev: A <:< Boolean): Remote[Boolean] =
    not(not(self.widen[Boolean]) && not(that))

  final def ifThenElse[B](ifTrue: Remote[B], ifFalse: Remote[B])(implicit ev: A <:< Boolean): Remote[B] =
    Remote.Branch(self.widen[Boolean], Remote.suspend(ifTrue), Remote.suspend(ifFalse))

  final def unary_!(implicit ev: A <:< Boolean): Remote[Boolean] =
    not(self.widen[Boolean])
}

private[zio] object RemoteBoolean {
  final def not(remote: Remote[Boolean]): Remote[Boolean] =
    Remote.Not(remote)
}
