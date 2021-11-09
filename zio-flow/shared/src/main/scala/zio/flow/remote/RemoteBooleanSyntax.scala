package zio.flow.remote

class RemoteBooleanSyntax(val self: Remote[Boolean]) {

  final def not: Remote[Boolean] = Remote.Not(self)

  final def &&(that: Remote[Boolean]): Remote[Boolean] =
    Remote.And(self, that)

  final def ||(that: Remote[Boolean]): Remote[Boolean] =
    (not && that.not).not

  final def ifThenElse[B](ifTrue: Remote[B], ifFalse: Remote[B]): Remote[B] =
    Remote.Branch(self, Remote.suspend(ifTrue), Remote.suspend(ifFalse))

  final def unary_! : Remote[Boolean] =
    not
}
