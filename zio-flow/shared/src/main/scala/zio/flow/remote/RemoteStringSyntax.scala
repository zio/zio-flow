package zio.flow.remote

class RemoteStringSyntax(self: Remote[String]) {
  def length: Remote[Int] = Remote.Length(self)
}
