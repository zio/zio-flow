package zio.flow

class RemoteStringSyntax(self: Remote[String]) {
  def ++(suffix: Remote[String]): Remote[String] = self.concat(suffix)

  def concat(suffix: Remote[String]): Remote[String] = Remote.StringToList(self) ++ Remote.StringToList(suffix)

  def length: Remote[Int] = Remote.Length(self)

  def reverse: Remote[String] = Remote.StringToList(self).reverse
}
