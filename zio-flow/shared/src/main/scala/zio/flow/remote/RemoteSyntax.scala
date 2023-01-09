package zio.flow.remote

import zio.flow.Remote
import zio.schema.Schema

final class RemoteSyntax[A](val self: Remote[A]) extends AnyVal {
  def toRemoteString(implicit schema: Schema[A]): Remote[String] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.ToString[A]()))
}
