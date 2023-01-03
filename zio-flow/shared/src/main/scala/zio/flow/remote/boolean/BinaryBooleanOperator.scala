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

package zio.flow.remote.boolean

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryBooleanOperator
object BinaryBooleanOperator {
  case object And extends BinaryBooleanOperator
  case object Or  extends BinaryBooleanOperator
  case object Xor extends BinaryBooleanOperator

  implicit val schema: Schema[BinaryBooleanOperator] = DeriveSchema.gen

  def evaluate(a: Boolean, b: Boolean, op: BinaryBooleanOperator): Boolean =
    op match {
      case And => a && b
      case Or  => a || b
      case Xor => a ^ b
    }
}
