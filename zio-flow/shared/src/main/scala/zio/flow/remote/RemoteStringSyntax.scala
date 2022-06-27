///*
// * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */

package zio.flow.remote

import zio.flow._

final class RemoteStringSyntax(val self: Remote[String]) extends AnyVal {
  import Remote.StringRemote._

  def charAt(ix: Remote[Int]): Remote[Option[Char]] =
    CharAt(self, ix)

  def codePointAt(ix: Remote[Int]): Remote[Option[Int]] =
    CodePointAt(self, ix)

  def codepointBefore(ix: Remote[Int]): Remote[Option[Int]] =
    CodePointBefore(self, ix)

  def compareTo(other: Remote[String]): Remote[Int] =
    CompareTo(self, other)

  def compareToIgnoreCase(other: Remote[String]): Remote[Int] =
    CompareToIgnoreCase(self, other)

  def concat(other: Remote[String]): Remote[String] =
    Concat(self, other)

  def contains(other: Remote[String]): Remote[Boolean] =
    Contains(self, other)

  def contentEquals(other: Remote[String]): Remote[Boolean] =
    ContentEquals(self, other)

  def endsWith(other: Remote[String]): Remote[Boolean] =
    EndsWith(self, other)

  def equal(other: Remote[String]): Remote[Boolean] =
    Equal(self, other)

  def equalsIgnoreCase(other: Remote[String]): Remote[Boolean] =
    EqualsIgnoreCase(self, other)

  def indexOf(ch: Remote[Int]): Remote[Int] =
    IndexOf(self, ch)

  def indexOfFromIndex(ch: Remote[Int], fr: Remote[Int]): Remote[Int] =
    IndexOfFromIndex(self, ch, fr)

  def indexOfString(st: Remote[String]): Remote[Int] =
    IndexOfString(self, st)

  def indexOfStringFromIndex(st: Remote[String], fr: Remote[Int]): Remote[Int] =
    IndexOfStringFromIndex(self, st, fr)

  def isEmpty: Remote[Boolean] =
    IsEmpty(self)

  def lastIndexOf(ch: Remote[Int]): Remote[Int] =
    LastIndexOf(self, ch)

  def lastIndexOfFromIndex(ch: Remote[Int], fr: Remote[Int]): Remote[Int] =
    LastIndexOfFromIndex(self, ch, fr)

  def lastIndexOfStringFromIndex(st: Remote[String], fr: Remote[Int]): Remote[Int] =
    LastIndexOfStringFromIndex(self, st, fr)

  def length: Remote[Int] =
    Length(self)

  def matches(rx: Remote[String]): Remote[Boolean] =
    Matches(self, rx)

  def offsetByCodePoints(ix: Remote[Int], cpo: Remote[Int]): Remote[Option[Int]] =
    OffsetByCodePoints(self, ix, cpo)

  def regionMatches(ignoreCase: Remote[Boolean], toffset: Remote[Int], other: Remote[String], ooffset: Remote[Int], len: Remote[Int]): Remote[Boolean] =
    RegionMatches(self, ignoreCase, toffset, other, ooffset, len)

  def replaceChar(oldChar: Remote[Char], newChar: Remote[Char]): Remote[String] =
    ReplaceChar(self, oldChar, newChar)

  def replaceString(target: Remote[String], replacement: Remote[String]): Remote[String] =
    ReplaceString(self, target, replacement)

  def replaceAll(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    ReplaceAll(self, regex, replacement)

  def replaceFirst(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    ReplaceAll(self, regex, replacement)

  def startsWith(prefix: Remote[String]): Remote[Boolean] =
    StartsWith(self, prefix)

  def startsWithOffset(prefix: Remote[String], toffset: Remote[Int]): Remote[Boolean] =
    StartsWithOffset(self, prefix, toffset)

  def subsequence(beginIndex: Remote[Int], endIndex: Remote[Int]): Remote[String] =
    Subsequence(self, beginIndex, endIndex)

  def substring(beginIndex: Remote[Int]): Remote[String] =
    Substring(self, beginIndex)

  def substringEndIndex(beginIndex: Remote[Int], endIndex: Remote[Int]): Remote[String] =
    SubstringEndIndex(self, beginIndex, endIndex)

  def trim: Remote[String] =
    Trim(self)

  def toList: Remote[List[Char]] =
    ToList(self)

  def head: Remote[Option[Char]] =
    Head(self)

  def tail: Remote[Option[String]] =
    Tail(self)

  def last: Remote[Option[Char]] =
    Last(self)

  def init: Remote[Option[String]] =
    Init(self)

  def tails: Remote[List[String]] =
    Tails(self)

  def inits: Remote[List[String]] =
    Inits(self)

  def drop(dropped: Remote[Int]): Remote[String] =
    Drop(self, dropped)

  def take(taken: Remote[Int]): Remote[String] =
    Take(self, taken)

  def zip(other: Remote[String]): Remote[List[(Char, Char)]] =
    Zip(self, other)

  def reverse: Remote[String] =
    Reverse(self)

  /*
  TODO

  dropWhile/takeWhile (?)
  repeat/replicate/iterate
  span/break
  partition :: (Char => Bool) => String => (String, String)
  lines/unlines/words/unwords
  intersect
  over: (Char => Char) => String => String
  filter: (Char => Bool) => String => String
  intersperse :: Char => String => String
  intercalate :: Char => String => String

  */
}
