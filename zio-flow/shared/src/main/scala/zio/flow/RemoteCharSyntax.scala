package zio.flow

class RemoteCharSyntax(self: Remote[Char]) {
  private[flow] def escape: Remote[String] =
    ((self >= 'a') && (self <= 'z') ||
      (self >= 'A') && (self <= 'Z') ||
      (self >= '0' && self <= '9')).ifThenElse(
      self.toString,
      "\\" + self.toString
    )

  def isLower: Remote[Boolean] =
    Remote.CharIsLower(self)

  def toInt: Remote[Int] =
    Remote.CharToInt(self)

  def toStringRemote: Remote[String] =
    Remote.ListToString(Remote.Cons(Remote(Nil), self))

  def toUpper: Remote[Char] =
    Remote.CharToUpper(self)
}
