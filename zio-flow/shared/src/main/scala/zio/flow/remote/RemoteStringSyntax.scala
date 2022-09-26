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
    StringToCharList(self)

  def length: Remote[Int] =
    Length(self)

  def charAt(ix: Remote[Int]): Remote[Char] =
    self.toList.apply(ix)

  def substring(begin: Remote[Int], end: Remote[Int]): Remote[String] =
    self.toList.slice(begin, end).mkString

  def isEmpty: Remote[Boolean] =
    self.length === 0

  def headOption: Remote[Option[Char]] =
    self.toList.headOption

  def lastOption: Remote[Option[Char]] =
    self.toList.lastOption

  def dropWhile(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.dropWhile(predicate).mkString

  def filter(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.filter(predicate).mkString

  def reverse: Remote[String] =
    self.toList.reverse.mkString
}
