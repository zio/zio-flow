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

sealed trait CharToCodeConversion

object CharToCodeConversion {
  case object AsDigit           extends CharToCodeConversion
  case object GetDirectionality extends CharToCodeConversion
  case object GetNumericValue   extends CharToCodeConversion
  case object GetType           extends CharToCodeConversion

  implicit val schema: Schema[CharToCodeConversion] = DeriveSchema.gen

  def evaluate(value: Char, operator: CharToCodeConversion): Int =
    operator match {
      case AsDigit           => value.asDigit
      case GetDirectionality => value.getDirectionality.toInt
      case GetNumericValue   => value.getNumericValue
      case GetType           => value.getType
    }
}
