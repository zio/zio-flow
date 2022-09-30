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

import zio.flow.remote.numeric.{BinaryIntegralOperator, BinaryFractionalOperator, BinaryNumericOperator}
import zio.schema._

sealed trait BinaryOperators[In, Out] {
  type Output = Out

  val inputSchema: Schema[In]
  val outputSchema: Schema[Out]

  def apply(left: In, right: In): Out
}

object BinaryOperators {
  def apply[A](operator: BinaryNumericOperator)(implicit numeric: zio.flow.remote.numeric.Numeric[A]): Numeric[A] =
    Numeric(operator, numeric)

  def apply[A](operator: BinaryFractionalOperator)(implicit
    fractional: zio.flow.remote.numeric.Fractional[A]
  ): Fractional[A] =
    Fractional(operator, fractional)

  def apply[A](operator: BinaryIntegralOperator)(implicit
    bitwise: zio.flow.remote.numeric.Integral[A]
  ): Integral[A] =
    Integral(operator, bitwise)

  final case class Numeric[A](operator: BinaryNumericOperator, numeric: zio.flow.remote.numeric.Numeric[A])
      extends BinaryOperators[A, A] {
    override val inputSchema: Schema[A]  = numeric.schema
    override val outputSchema: Schema[A] = numeric.schema

    override def apply(left: A, right: A): A =
      numeric.binary(operator, left, right)
  }

  final case class Fractional[A](operator: BinaryFractionalOperator, fractional: zio.flow.remote.numeric.Fractional[A])
      extends BinaryOperators[A, A] {
    override val inputSchema: Schema[A]  = fractional.schema
    override val outputSchema: Schema[A] = fractional.schema

    override def apply(left: A, right: A): A =
      fractional.binary(operator, left, right)
  }

  final case class Integral[A](operator: BinaryIntegralOperator, bitwise: zio.flow.remote.numeric.Integral[A])
      extends BinaryOperators[A, A] {
    override val inputSchema: Schema[A]  = bitwise.schema
    override val outputSchema: Schema[A] = bitwise.schema

    override def apply(left: A, right: A): A =
      bitwise.binary(operator, left, right)
  }

  private val numericCase: Schema.Case[Numeric[Any], BinaryOperators[Any, Any]] =
    Schema.Case(
      "Numeric",
      Schema.CaseClass2(
        TypeId.parse("zio.flow.remote.BinaryOperators.Numeric"),
        Schema.Field("operator", Schema[BinaryNumericOperator]),
        Schema.Field("numeric", zio.flow.remote.numeric.Numeric.schema),
        (op: BinaryNumericOperator, n: zio.flow.remote.numeric.Numeric[Any]) => Numeric(op, n),
        _.operator,
        _.numeric
      ),
      _.asInstanceOf[Numeric[Any]]
    )

  private val fractionalCase: Schema.Case[Fractional[Any], BinaryOperators[Any, Any]] =
    Schema.Case(
      "Fractional",
      Schema.CaseClass2(
        TypeId.parse("zio.flow.remote.BinaryOperators.Fractional"),
        Schema.Field("operator", Schema[BinaryFractionalOperator]),
        Schema.Field("fractional", zio.flow.remote.numeric.Fractional.schema),
        (op: BinaryFractionalOperator, f: zio.flow.remote.numeric.Fractional[Any]) => Fractional(op, f),
        _.operator,
        _.fractional
      ),
      _.asInstanceOf[Fractional[Any]]
    )

  private val integralCase: Schema.Case[Integral[Any], BinaryOperators[Any, Any]] =
    Schema.Case(
      "Integral",
      Schema.CaseClass2(
        TypeId.parse("zio.flow.remote.BinaryOperators.Integral"),
        Schema.Field("operator", Schema[BinaryIntegralOperator]),
        Schema.Field("fractional", zio.flow.remote.numeric.Integral.schema),
        (op: BinaryIntegralOperator, b: zio.flow.remote.numeric.Integral[Any]) => Integral(op, b),
        _.operator,
        _.bitwise
      ),
      _.asInstanceOf[Integral[Any]]
    )

  def schema[In, Out]: Schema[BinaryOperators[In, Out]] = schemaAny.asInstanceOf[Schema[BinaryOperators[In, Out]]]

  val schemaAny: Schema[BinaryOperators[Any, Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.BinaryOperators"),
      CaseSet
        .Cons(
          numericCase,
          CaseSet.Empty[BinaryOperators[Any, Any]]()
        )
        .:+:(fractionalCase)
        .:+:(integralCase)
    )
}
