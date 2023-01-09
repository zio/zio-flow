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

package zio.flow.remote.text

import zio.schema.{DeriveSchema, Schema}

sealed trait CharPredicateOperator

object CharPredicateOperator {
  case object IsControl                extends CharPredicateOperator
  case object IsDigit                  extends CharPredicateOperator
  case object IsHighSurrogate          extends CharPredicateOperator
  case object IsIdentifierIgnorable    extends CharPredicateOperator
  case object IsLetter                 extends CharPredicateOperator
  case object IsLetterOrDigit          extends CharPredicateOperator
  case object IsLowSurrogate           extends CharPredicateOperator
  case object IsLower                  extends CharPredicateOperator
  case object IsMirrored               extends CharPredicateOperator
  case object IsSpaceChar              extends CharPredicateOperator
  case object IsSurrogate              extends CharPredicateOperator
  case object IsTitleCase              extends CharPredicateOperator
  case object IsUnicodeIdentifierPart  extends CharPredicateOperator
  case object IsUnicodeIdentifierStart extends CharPredicateOperator
  case object IsUpper                  extends CharPredicateOperator
  case object IsWhitespace             extends CharPredicateOperator

  implicit val schema: Schema[CharPredicateOperator] = DeriveSchema.gen

  def evaluate(ch: Char, operator: CharPredicateOperator): Boolean =
    operator match {
      case IsControl                => ch.isControl
      case IsDigit                  => ch.isDigit
      case IsHighSurrogate          => ch.isHighSurrogate
      case IsIdentifierIgnorable    => ch.isIdentifierIgnorable
      case IsLetter                 => ch.isLetter
      case IsLetterOrDigit          => ch.isLetterOrDigit
      case IsLowSurrogate           => ch.isLowSurrogate
      case IsLower                  => ch.isLower
      case IsMirrored               => ch.isMirrored
      case IsSpaceChar              => ch.isSpaceChar
      case IsSurrogate              => ch.isSurrogate
      case IsTitleCase              => ch.isTitleCase
      case IsUnicodeIdentifierPart  => ch.isUnicodeIdentifierPart
      case IsUnicodeIdentifierStart => ch.isUnicodeIdentifierStart
      case IsUpper                  => ch.isUpper
      case IsWhitespace             => ch.isWhitespace
    }
}
