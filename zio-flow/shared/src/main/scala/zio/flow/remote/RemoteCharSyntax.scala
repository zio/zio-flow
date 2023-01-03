/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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
import zio.flow.remote.text.{CharConversion, CharPredicateOperator, CharToCodeConversion}

final class RemoteCharSyntax(val self: Remote[Char]) extends AnyVal {

  def asDigit: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToCode(CharToCodeConversion.AsDigit)))

  def getDirectionality: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToCode(CharToCodeConversion.GetDirectionality)))

  def getNumericValue: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToCode(CharToCodeConversion.GetNumericValue)))

  def getType: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToCode(CharToCodeConversion.GetType)))

  def isControl: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsControl))

  def isDigit: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsDigit))

  def isHighSurrogate: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsHighSurrogate))

  def isIdentifierIgnorable: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsIdentifierIgnorable))

  def isLetter: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsLetter))

  def isLetterOrDigit: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsLetterOrDigit))

  def isLowSurrogate: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsLowSurrogate))

  def isLower: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsLower))

  def isMirrored: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsMirrored))

  def isSpaceChar: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsSpaceChar))

  def isSurrogate: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsSurrogate))

  def isTitleCase: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsTitleCase))

  def isUnicodeIdentifierPart: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsUnicodeIdentifierPart))

  def isUnicodeIdentifierStart: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsUnicodeIdentifierStart))

  def isUpper: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsUpper))

  def isWhitespace: Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(CharPredicateOperator.IsWhitespace))

  def reverseBytes: Remote[Char] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToChar(CharConversion.ReverseBytes)))

  def toLower: Remote[Char] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToChar(CharConversion.ToLower)))

  def toTitleCase: Remote[Char] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToChar(CharConversion.ToTitleCase)))

  def toUpper: Remote[Char] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.CharToChar(CharConversion.ToUpper)))
}
