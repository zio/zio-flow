package zio.flow

class RemoteCharSyntax(self: Remote[Char]) {
  def toInt: Remote[Int] =
    Remote.CharToInt(self)
}
