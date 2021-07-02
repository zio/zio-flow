package zio.flow

class RemoteStringSyntax(self: Remote[String]) {
  def length: Remote[Int] = Remote.Length(self)
}
