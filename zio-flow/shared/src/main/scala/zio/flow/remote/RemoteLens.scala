/*
 * Copyright 2019-2021 John A. De Goes and the ZIO Contributors
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

import zio.schema._

import scala.collection.immutable.ListMap

/**
 * A `RemoteLens[S, A]` knows how to access a field `A` of a product type `S`
 * in the context of a remote value.
 */
sealed trait RemoteLens[S, A] { self =>

  /**
   * The schema describing the structure of the type `A` being accessed by this
   * lens.
   */
  def schemaPiece: Schema[A]

  /**
   * The schema describing the structure of the type `S` being access by this
   * lens.
   */
  def schemaWhole: Schema[S]

  /**
   * Gets the value of the field of the specified product type.
   */
  def get(s: Remote[S]): Remote[A] =
    Remote.LensGet(s, self)

  /**
   * Sets the field of the specified product type to the specified value.
   */
  def set(s: Remote[S])(a: Remote[A]): Remote[S] =
    Remote.LensSet(s, a, self)

  /**
   * Updates the value of the field of the specified product type with the
   * specified function.
   */
  def update(s: Remote[S])(f: Remote[A] => Remote[A]): Remote[S] =
    set(s)(f(get(s)))

  /**
   * Gets the value of the field of the specified product type
   * outside the context of a remote value.
   */
  private[remote] def unsafeGet(s: S): A

  /**
   * Sets the field of the specified product type to the specified value
   * outside the context of a remote value.
   */
  private[remote] def unsafeSet(a: A)(s: S): S
}

object RemoteLens {

  /**
   * Unsafely constructs a remote lens from a description of the structure of
   * the product type and field. The caller is responsible for ensuring that
   * the field exists in the product type.
   */
  private[remote] def unsafeMake[S, A](product: Schema.Record[S], field: Schema.Field[A]): RemoteLens[S, A] =
    Primitive(product, field)

  /**
   * A remote lens formed directly from the structure of a product type and a
   * field of that product type.
   */
  private final case class Primitive[S, A](product: Schema.Record[S], field: Schema.Field[A]) extends RemoteLens[S, A] {
    val schemaPiece: Schema[A] = field.schema
    val schemaWhole: Schema[S] = product

    def unsafeGet(s: S): A =
      product.toDynamic(s) match {
        case DynamicValue.Record(values) =>
          values
            .get(field.label)
            .map { dynamicField =>
              field.schema.fromDynamic(dynamicField) match {
                case Left(error)  => throw new IllegalStateException(error)
                case Right(value) => value
              }
            }
            .getOrElse(throw new IllegalStateException(s"No field found with label ${field.label}"))
        case _                           => throw new IllegalStateException("Unexpected dynamic value for product type")
      }

    def unsafeSet(a: A)(s: S): S =
      product.toDynamic(s) match {
        case DynamicValue.Record(values) =>
          val updated = spliceRecord(values, field.label, field.label -> field.schema.toDynamic(a))
          product.fromDynamic(DynamicValue.Record(updated)) match {
            case Left(error)  => throw new IllegalStateException(error)
            case Right(value) => value
          }
        case _                           => throw new IllegalStateException("Unexpected dynamic value for product type")
      }

    def spliceRecord(
      fields: ListMap[String, DynamicValue],
      label: String,
      splicedField: (String, DynamicValue)
    ): ListMap[String, DynamicValue] =
      fields.foldLeft[ListMap[String, DynamicValue]](ListMap.empty) {
        case (acc, (nextLabel, _)) if nextLabel == label => acc + splicedField
        case (acc, nextField)                            => acc + nextField
      }
  }
}
