package zio.flow

class RemoteStringSyntax(self: Remote[String]) {
  def ++(suffix: Remote[String]): Remote[String] = self.concat(suffix)

  def charAtOption(index: Remote[Int]): Remote[Option[Char]] =
    Remote.CharAtOption(self, index)

  def codepointAtOption(index: Remote[Int]): Remote[Option[Int]] =
    Remote.CodepointAtOption(self, index)

  def codepointBeforeOption(index: Remote[Int]): Remote[Option[Int]] =
    Remote.CodepointBeforeOption(self, index)

  def compareToIgnoreCase(that: Remote[String]): Remote[Int] =
    Remote.CompareIgnoreCase(self, that)

  def concat(suffix: Remote[String]): Remote[String] = toList ++ suffix.toList

  def contains(char: Remote[Char])(implicit d: DummyImplicit): Remote[Boolean] =
    toList.contains(char)

  def contains(substring: Remote[String]): Remote[Boolean] =
    toList.containsSlice(substring.toList)

  def length: Remote[Int] = Remote.Length(self)

  def reverse: Remote[String] = toList.reverse

  def toList: Remote[List[Char]] = Remote.StringToList(self)
}
