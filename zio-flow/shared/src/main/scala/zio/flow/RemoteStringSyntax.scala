package zio.flow

import java.util.Locale

class RemoteStringSyntax(self: Remote[String]) {
  def +(suffix: Remote[String]): Remote[String] = self.concat(suffix)

  def ++(suffix: Remote[String]): Remote[String] = self.concat(suffix)

  def charAtOption(index: Remote[Int]): Remote[Option[Char]] =
    Remote.CharAtOption(self, index)

  def codepointAtOption(index: Remote[Int]): Remote[Option[Int]] =
    Remote.CodepointAtOption(self, index)

  def codepointBeforeOption(index: Remote[Int]): Remote[Option[Int]] =
    Remote.CodepointBeforeOption(self, index)

  def compareToIgnoreCase(that: Remote[String]): Remote[Int] =
    Remote.CompareIgnoreCase(self, that)

  def concat(suffix: Remote[String]): Remote[String] =
    Remote.ListToString(toList.concat(suffix.toList))

  def contains(char: Remote[Char])(implicit d: DummyImplicit): Remote[Boolean] =
    RemoteStringToListChar(self).contains(char)

  def contains(substring: Remote[String]): Remote[Boolean] =
    toList.containsSlice(substring.toList)

  def drop(n: Remote[Int]): Remote[String] =
    Remote.ListToString(RemoteStringToListChar(self).drop(n))

  def endsWith(s: Remote[String]): Remote[Boolean] =
    toList.endsWith(s.toList)

  def equalsIgnoreCase(anotherString: Remote[String]): Remote[Boolean] =
    self.toLowerCase === anotherString.toLowerCase

  def indexOf(ch: Remote[Char]): Remote[Int] =
    indexOf(ch, 0)

  def indexOf(ch: Remote[Char], fromIndex: Remote[Int]): Remote[Int] =
    Remote.IndexOfCharFromIndex(self, ch.toInt, fromIndex)

  def indexOf(ch: Remote[Int])(implicit d: DummyImplicit): Remote[Int] =
    indexOf(ch, 0)

  def indexOf(ch: Remote[Int], fromIndex: Remote[Int])(implicit d: DummyImplicit): Remote[Int] =
    Remote.IndexOfCharFromIndex(self, ch, fromIndex)

  def indexOf(str: Remote[String])(implicit d: DummyImplicit, e: DummyImplicit): Remote[Int] =
    indexOf(str, 0)(d, e)

  def indexOf(str: Remote[String], fromIndex: Remote[Int])(implicit d: DummyImplicit, e: DummyImplicit): Remote[Int] =
    Remote.IndexOfStringFromIndex(self, str, fromIndex)

  def lastIndexOf(ch: Remote[Char]): Remote[Int] =
    lastIndexOf(ch, length)

  def lastIndexOf(ch: Remote[Char], fromIndex: Remote[Int]): Remote[Int] =
    Remote.LastIndexOfCharFromIndex(self, ch.toInt, fromIndex)

  def lastIndexOf(ch: Remote[Int])(implicit d: DummyImplicit): Remote[Int] =
    lastIndexOf(ch, length)

  def lastIndexOf(ch: Remote[Int], fromIndex: Remote[Int])(implicit d: DummyImplicit): Remote[Int] =
    Remote.LastIndexOfCharFromIndex(self, ch, fromIndex)

  def lastIndexOf(str: Remote[String])(implicit d: DummyImplicit, e: DummyImplicit): Remote[Int] =
    Remote.LastIndexOfStringFromIndex(self, str, length)

  def lastIndexOf(str: Remote[String], fromIndex: Remote[Int])(implicit
    d: DummyImplicit,
    e: DummyImplicit
  ): Remote[Int] =
    Remote.LastIndexOfStringFromIndex(self, str, fromIndex)

  def length: Remote[Int] = Remote.Length(self)

  def matches(regex: Remote[String]): Remote[Boolean] =
    Remote.MatchesRegex(self, regex)

  def offsetByCodePoints(index: Remote[Int], codePointOffset: Remote[Int]): Remote[Int] =
    Remote.OffsetByCodePoints(self, index, codePointOffset)

  def regionMatches(
    toffset: Remote[Int],
    other: Remote[String],
    ooffset: Remote[Int],
    len: Remote[Int]
  ): Remote[Boolean] = {
    val invalidParams = (ooffset < 0) || (toffset < 0) ||
      (Remote.IntToLong(toffset) > Remote.IntToLong(self.length) - Remote.IntToLong(len)) ||
      (Remote.IntToLong(ooffset) > Remote.IntToLong(other.length) - Remote.IntToLong(len))
    invalidParams.ifThenElse(
      false,
      slice(toffset, toffset + len) === other.slice(ooffset, ooffset + len)
    )
  }

  def repeat(count: Remote[Int]): Remote[String] =
    (count > 0).ifThenElse(self + repeat(count - 1), "")

  def replace(oldChar: Remote[Char], newChar: Remote[Char]): Remote[String] =
    Remote.ListToString(
      toList.fold[List[Char]](Nil) { (chars, char) =>
        Remote.Cons(chars, (char === oldChar).ifThenElse(newChar, char))
      }
    )

  def replace(target: Remote[String], replacement: Remote[String])(implicit d: DummyImplicit): Remote[String] =
    (target === "").ifThenElse(
      replacement + self.headOption.handleOption("", ch => Remote.ListToString(Remote.Cons(Remote(Nil), ch))) +
        self.isEmpty.ifThenElse(self, self.drop(1).replace(target, replacement)), {
        val occurrence = self.indexOf(target)
        (occurrence === -1).ifThenElse(
          self,
          self.take(occurrence) ++ replacement ++ self.drop(occurrence + target.length).replace(target, replacement)
        )
      }
    )

  def replaceAll(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    Remote.ReplaceAll(self, regex, replacement)

  def reverse: Remote[String] =
    Remote.ListToString(toList.reverse)

  def slice(from: Remote[Int], until: Remote[Int]): Remote[String] =
    substringOption(from, until).getOrElse("")

  def substringOption(beginIndex: Remote[Int], endIndex: Remote[Int] = length): Remote[Option[String]] =
    ((beginIndex < 0) || (endIndex > length) || (beginIndex > endIndex))
      .ifThenElse(
        None,
        Remote.Some0(drop(beginIndex).take(endIndex))
      )

  def take(n: Remote[Int]): Remote[String] =
    Remote.ListToString(toList.take(n))

  def toList: Remote[List[Char]] =
    Remote.StringToList(self)

  /**
   * Uses [[Locale.ROOT]] instead of the default locale for this instance of the JVM. A different locale can be
   * specified by calling `toLowerCase(locale: Remote[Locale])`.
   */
  def toLowerCase: Remote[String] =
    toLowerCase(Locale.ROOT)

  def toLowerCase(locale: Remote[Locale]): Remote[String] =
    Remote.ToLowerCase(self, locale)

  /**
   * Uses [[Locale.ROOT]] instead of the default locale for this instance of the JVM. A different locale can be
   * specified by calling `toUpperCase(locale: Remote[Locale])`.
   */
  def toUpperCase: Remote[String] =
    toUpperCase(Locale.ROOT)

  def toUpperCase(locale: Remote[Locale]): Remote[String] =
    Remote.ToUpperCase(self, locale)
}
