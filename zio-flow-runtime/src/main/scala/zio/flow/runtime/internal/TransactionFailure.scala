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

package zio.flow.runtime.internal

import zio.schema.{DynamicValue, Schema, TypeId}

sealed trait TransactionFailure[+E]
object TransactionFailure {
  case object Retry                  extends TransactionFailure[Nothing]
  final case class Fail[E](value: E) extends TransactionFailure[E]

  private val typeId: TypeId = TypeId.parse("zio.flow.runtime.internal.TransactionFailure")

  implicit def schema[E: Schema]: Schema[TransactionFailure[E]] =
    Schema.Enum2(
      typeId,
      Schema.Case[Retry.type, TransactionFailure[E]](
        "Retry",
        Schema.singleton(Retry),
        _.asInstanceOf[Retry.type]
      ),
      Schema.Case[TransactionFailure.Fail[E], TransactionFailure[E]](
        "Fail",
        implicitly[Schema[E]].transform(Fail(_), _.value),
        _.asInstanceOf[Fail[E]]
      )
    )

  /** Wraps a dynamic value E with TransactionFailure.Fail(value) */
  def wrapDynamic[E](value: DynamicValue): DynamicValue =
    DynamicValue.Enumeration(typeId, "Fail" -> value)

  /**
   * Unwraps a dynamic value of type TransactionFailure.Fail or returns None if
   * it was TransactionFailure.Retry
   */
  def unwrapDynamic(dynamicValue: DynamicValue): Option[DynamicValue] =
    dynamicValue match {
      case DynamicValue.Enumeration(_, (name, value)) =>
        name match {
          case "Retry" => None
          case "Fail" =>
            Some(value)
          case _ =>
            throw new IllegalArgumentException(s"TransactionFailure.unwrapDynamic called with an unexpected schema")
        }
      case _ =>
        throw new IllegalArgumentException(s"TransactionFailure.unwrapDynamic called with an unexpected dynamic value")
    }
}
