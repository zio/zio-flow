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

import zio.schema.{DynamicValue, Schema}

object DynamicValueHelpers {

  def of[A: Schema](value: A): DynamicValue =
    DynamicValue.fromSchemaAndValue(Schema[A], value)

  def tuple(values: DynamicValue*): DynamicValue =
    values.toList match {
      case (left :: last :: Nil)   => DynamicValue.Tuple(left, last)
      case (left :: right :: rest) => tuple(DynamicValue.Tuple(left, right) :: rest: _*)
      case _                       => throw new IllegalArgumentException(s"DynamicValueHelpers.tuple requires at least two parameters")
    }
}
