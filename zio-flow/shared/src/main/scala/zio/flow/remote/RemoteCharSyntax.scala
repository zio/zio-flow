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

final class RemoteCharSyntax(val self: Remote[Char]) extends AnyVal {

  def asDigit: Remote[Int] =
    ???

  def getDirectionality: Remote[Byte] =
    ???

  def getNumericValue: Remote[Int] =
    ???

  def getType: Remote[Int] =
    ???

  def isControl: Remote[Boolean] =
    ???

  def isDigit: Remote[Boolean] =
    ???

  def isHighSurrogate: Remote[Boolean] =
    ???

  def isIdentifierIgnorable: Remote[Boolean] =
    ???

  def isLetter: Remote[Boolean] =
    ???

  def isLetterOrDigit: Remote[Boolean] =
    ???

  def isControl: Remote[Boolean] =
    ???

  def isLowSurrogate: Remote[Boolean] =
    ???

  def isLower: Remote[Boolean] =
    ???

  def isMirrored: Remote[Boolean] =
    ???

  def isSpaceChar: Remote[Boolean] =
    ???

  def isSurrogate: Remote[Boolean] =
    ???

  def isTitleCase: Remote[Boolean] =
    ???

  def isUnicodeIdentifierPart: Remote[Boolean] =
    ???

  def isUnicodeIdentifierStart: Remote[Boolean] =
    ???

  def isUpper: Remote[Boolean] =
    ???

  def isWhitespace: Remote[Boolean] =
    ???

  def reverseBytes: Remote[Char] =
    ???

  def toLower: Remote[Char] =
    ???

  def toTitleCase: Remote[Char] =
    ???

  def toUpper: Remote[Char] =
    ???
}
