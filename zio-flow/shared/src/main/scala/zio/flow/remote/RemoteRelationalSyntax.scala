package zio.flow.remote

import zio.flow._

class RemoteRelationalSyntax[A](self: Remote[A]) {

  final def <(that: Remote[A]): Remote[Boolean] =
    (self <= that) && (self !== that)

  final def <=(that: Remote[A]): Remote[Boolean] =
    Remote.LessThanEqual(self, that)

  final def >(that: Remote[A]): Remote[Boolean] =
    !(self <= that)

  final def >=(that: Remote[A]): Remote[Boolean] =
    (self > that) || (self === that)

  final def !==(that: Remote[A]): Remote[Boolean] =
    !(self === that)

  final def ===(that: Remote[A]): Remote[Boolean] =
    Remote.Equal(self, that)
}
