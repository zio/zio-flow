package zio.flow.remote

import zio.flow._

class RemoteStringSyntax(self: Remote[String]) {
  def length: Remote[Int] = Remote.Length(self)
}
