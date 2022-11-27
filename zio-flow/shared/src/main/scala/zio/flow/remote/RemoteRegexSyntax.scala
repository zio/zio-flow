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

import zio.flow.Remote

import scala.util.matching.Regex

final class RemoteRegexSyntax(val self: Remote[Regex]) extends AnyVal {

  def findFirstIn(source: Remote[String]): Remote[Option[String]] =
    Remote.Binary(self, source, BinaryOperators.RegexFindFirstIn)

  def findMatches(s: Remote[String]): Remote[Option[List[String]]] =
    Remote.Binary(self, s, BinaryOperators.RegexUnapplySeq)

  def matches(source: Remote[String]): Remote[Boolean] =
    Remote.Binary(self, source, BinaryOperators.RegexMatches)

  def replaceAllIn(source: Remote[String], replacement: Remote[String]): Remote[String] =
    Remote.Binary(self, (source, replacement), BinaryOperators.RegexReplaceAllIn)

  def replaceFirstIn(source: Remote[String], replacement: Remote[String]): Remote[String] =
    Remote.Binary(self, (source, replacement), BinaryOperators.RegexReplaceFirstIn)

  def split(toSplit: Remote[String]): Remote[List[String]] =
    Remote.Binary(self, toSplit, BinaryOperators.RegexSplit)

  def regex: Remote[String] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.RegexToString))
}
