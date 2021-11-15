package zio.flow.remote

import zio.flow.remote.Remote.ContainsOption
import zio.schema.DeriveSchema.gen

class RemoteOptionSyntax[A](val self: Remote[Option[A]]) {

  def handleOption[B](forNone: Remote[B], f: Remote[A] => Remote[B]): Remote[B] =
    Remote.FoldOption(self, forNone, f)

  def isSome: Remote[Boolean] =
    handleOption(Remote(false), _ => Remote(true))

  def isNone: Remote[Boolean] =
    handleOption(Remote(true), _ => Remote(false))

  final def isEmpty: Remote[Boolean] = isNone

  final def isDefined: Remote[Boolean] = !isEmpty

  final def knownSize: Remote[Int] = handleOption(Remote(0), _ => Remote(1))

  final def contains[A1 >: A](elem: A1): Remote[Boolean] = ContainsOption(self, elem)

  def orElse[B >: A](alternative: Remote[Option[B]]): Remote[Option[B]] = handleOption(alternative, _ => self)

  final def zip[A1 >: A, B](that: Remote[Option[B]]): Remote[Option[(A1, B)]] = Remote.ZipOption(self, that)
}
