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

sealed trait CharConversion

object CharConversion {
  case object ToUpper      extends CharConversion
  case object ToLower      extends CharConversion
  case object ToTitleCase  extends CharConversion
  case object ReverseBytes extends CharConversion

  implicit val schema: Schema[CharConversion] = DeriveSchema.gen

  def evaluate(value: Char, operator: CharConversion): Char =
    operator match {
      case ToUpper      => value.toUpper
      case ToLower      => value.toLower
      case ToTitleCase  => value.toTitleCase
      case ReverseBytes => value.reverseBytes
    }
}
