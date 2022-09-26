/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.remote

import zio.flow._

final class RemoteStringSyntax(val self: Remote[String]) extends AnyVal {
  import Remote._

  def toList: Remote[List[Char]] =
    StringToList(self)

  def length: Remote[Int] =
    Length(self)

  def charAt(ix: Remote[Int]): Remote[Option[Char]] =
    CharAt(self, ix)

  def substring(begin: Remote[Int], end: Remote[Int]): Remote[Option[String]] =
    Substring(self, begin, end)

  def isEmpty: Remote[Boolean] =
    Equal(length, Remote(0))

  def headOption: Remote[Option[Char]] =
    charAt(Remote(0))

  def tailOption: Remote[Option[String]] =
    substring(1, length)

  def lastOption: Remote[Option[Char]] =
    charAt(length - 1)

  def initOption: Remote[Option[String]] =
    substring(0, length - 1)

  def each(f: Remote[Char] => Remote[String]): Remote[String] =
    toList.each(c => f(c).toList).asString

  // def zip(y: Remote[String]): Remote[List[(Char, Char)]] =
  //   toList zip y.toList

  // def takeWhile(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
  //   toList.takeWhile(predicate).asString

  def dropWhile(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
    toList.dropWhile(predicate).asString

  def filter(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
    toList.filter(predicate).asString

  def reverse: Remote[String] =
    toList.reverse.asString
}
