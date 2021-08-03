package zio.flow

import scala.util.Try

class RemoteStringSyntax(self: Remote[String]) {
  def ++(suffix: Remote[String]): Remote[String] = self.concat(suffix)

  def charAtOption(index: Remote[Int]): Remote[Option[Char]] =
    Remote.ZipWith(self, index)((string, index) => Try(string.charAt(index)).toOption)

  def codepointAtOption(index: Remote[Int]): Remote[Option[Int]] =
    Remote.ZipWith(self, index)((string, index) => Try(string.codePointAt(index)).toOption)

  def codepointBeforeOption(index: Remote[Int]): Remote[Option[Int]] =
    Remote.ZipWith(self, index)((string, index) => Try(string.codePointBefore(index)).toOption)

  def compare(that: Remote[String]): Remote[Int] =
    Remote.ZipWith(self, that)(_.compare(_))

  def concat(suffix: Remote[String]): Remote[String] = toList ++ suffix.toList

  def length: Remote[Int] = Remote.Length(self)

  def reverse: Remote[String] = toList.reverse

  def toList: Remote[List[Char]] = Remote.StringToList(self)
}
