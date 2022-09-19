package zio.flow.remote

import zio.flow.Remote

final class RemoteSetSyntax[A](val self: Remote[Set[A]]) extends AnyVal {

  def toList: Remote[List[A]] =
    Remote.SetToList(self)
}
