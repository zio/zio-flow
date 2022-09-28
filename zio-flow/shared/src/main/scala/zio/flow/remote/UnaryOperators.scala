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

import zio.flow.remote.numeric.{
  FractionalPredicateOperator,
  NumericPredicateOperator,
  UnaryFractionalOperator,
  UnaryIntegralOperator,
  UnaryNumericOperator
}
import zio.flow.remote.text.CharPredicateOperator
import zio.schema.{CaseSet, Schema}

sealed trait UnaryOperators[In, Out] {
  type Output = Out

  val inputSchema: Schema[In]
  val outputSchema: Schema[Out]
  def apply(value: In): Out
}

object UnaryOperators {
  def apply[A](operator: UnaryNumericOperator)(implicit numeric: zio.flow.remote.numeric.Numeric[A]): Numeric[A] =
    Numeric(operator, numeric)

  def apply[A](operator: UnaryIntegralOperator)(implicit
    bitwise: zio.flow.remote.numeric.Integral[A]
  ): Integral[A] =
    Integral(operator, bitwise)

  def apply[A](operator: UnaryFractionalOperator)(implicit
    fractional: zio.flow.remote.numeric.Fractional[A]
  ): Fractional[A] =
    Fractional(operator, fractional)

  def apply[A](operator: NumericPredicateOperator)(implicit
    numeric: zio.flow.remote.numeric.Numeric[A]
  ): NumericPredicate[A] =
    NumericPredicate(operator, numeric)

  def apply[A](operator: FractionalPredicateOperator)(implicit
    numeric: zio.flow.remote.numeric.Fractional[A]
  ): FractionalPredicate[A] =
    FractionalPredicate(operator, numeric)

  def apply(operator: CharPredicateOperator): CharPredicate =
    CharPredicate(operator)

  final case class Numeric[A](operator: UnaryNumericOperator, numeric: zio.flow.remote.numeric.Numeric[A])
      extends UnaryOperators[A, A] {
    override val inputSchema: Schema[A]  = numeric.schema
    override val outputSchema: Schema[A] = numeric.schema
    override def apply(value: A): A =
      numeric.unary(operator, value)
  }
  final case class Integral[A](operator: UnaryIntegralOperator, integral: zio.flow.remote.numeric.Integral[A])
      extends UnaryOperators[A, A] {
    override val inputSchema: Schema[A]  = integral.schema
    override val outputSchema: Schema[A] = integral.schema
    override def apply(value: A): A =
      integral.unary(operator, value)
  }
  final case class Fractional[A](operator: UnaryFractionalOperator, fractional: zio.flow.remote.numeric.Fractional[A])
      extends UnaryOperators[A, A] {
    override val inputSchema: Schema[A]  = fractional.schema
    override val outputSchema: Schema[A] = fractional.schema
    override def apply(value: A): A =
      fractional.unary(operator, value)
  }
  final case class NumericPredicate[A](operator: NumericPredicateOperator, numeric: zio.flow.remote.numeric.Numeric[A])
      extends UnaryOperators[A, Boolean] {
    override val inputSchema: Schema[A]        = numeric.schema
    override val outputSchema: Schema[Boolean] = Schema[Boolean]

    override def apply(value: A): Boolean =
      numeric.predicate(operator, value)
  }

  final case class FractionalPredicate[A](
    operator: FractionalPredicateOperator,
    fractional: zio.flow.remote.numeric.Fractional[A]
  ) extends UnaryOperators[A, Boolean] {
    override val inputSchema: Schema[A]        = fractional.schema
    override val outputSchema: Schema[Boolean] = Schema[Boolean]

    override def apply(value: A): Boolean =
      fractional.predicate(operator, value)
  }

  final case class CharPredicate(operator: CharPredicateOperator) extends UnaryOperators[Char, Boolean] {
    override val inputSchema: Schema[Char]     = Schema[Char]
    override val outputSchema: Schema[Boolean] = Schema[Boolean]

    override def apply(value: Char): Boolean =
      CharPredicateOperator.evaluate(value, operator)
  }

  final case class Conversion[In, Out](
    conversion: RemoteConversions[In, Out]
  ) extends UnaryOperators[In, Out] {
    override val inputSchema: Schema[In]   = conversion.inputSchema
    override val outputSchema: Schema[Out] = conversion.outputSchema

    override def apply(value: In): Out = conversion(value)
  }

  private val numericCase: Schema.Case[Numeric[Any], UnaryOperators[Any, Any]] =
    Schema.Case(
      "Numeric",
      Schema.CaseClass2(
        Schema.Field("operator", Schema[UnaryNumericOperator]),
        Schema.Field("numeric", zio.flow.remote.numeric.Numeric.schema),
        (op: UnaryNumericOperator, n: zio.flow.remote.numeric.Numeric[Any]) => Numeric(op, n),
        _.operator,
        _.numeric
      ),
      _.asInstanceOf[Numeric[Any]]
    )

  private val integralCase: Schema.Case[Integral[Any], UnaryOperators[Any, Any]] =
    Schema.Case(
      "Integral",
      Schema.CaseClass2(
        Schema.Field("operator", Schema[UnaryIntegralOperator]),
        Schema.Field("integral", zio.flow.remote.numeric.Integral.schema),
        (op: UnaryIntegralOperator, b: zio.flow.remote.numeric.Integral[Any]) => Integral(op, b),
        _.operator,
        _.integral
      ),
      _.asInstanceOf[Integral[Any]]
    )

  private val fractionalCase: Schema.Case[Fractional[Any], UnaryOperators[Any, Any]] =
    Schema.Case(
      "Fractional",
      Schema.CaseClass2(
        Schema.Field("operator", Schema[UnaryFractionalOperator]),
        Schema.Field("fractional", zio.flow.remote.numeric.Fractional.schema),
        (op: UnaryFractionalOperator, f: zio.flow.remote.numeric.Fractional[Any]) => Fractional(op, f),
        _.operator,
        _.fractional
      ),
      _.asInstanceOf[Fractional[Any]]
    )

  private val numericPredicateCase: Schema.Case[NumericPredicate[Any], UnaryOperators[Any, Any]] =
    Schema.Case(
      "NumericPredicate",
      Schema.CaseClass2(
        Schema.Field("operator", Schema[NumericPredicateOperator]),
        Schema.Field("numeric", zio.flow.remote.numeric.Numeric.schema),
        (op: NumericPredicateOperator, n: zio.flow.remote.numeric.Numeric[Any]) => NumericPredicate(op, n),
        _.operator,
        _.numeric
      ),
      _.asInstanceOf[NumericPredicate[Any]]
    )

  private val fractionalPredicateCase: Schema.Case[FractionalPredicate[Any], UnaryOperators[Any, Any]] =
    Schema.Case(
      "FractionalPredicate",
      Schema.CaseClass2(
        Schema.Field("operator", Schema[FractionalPredicateOperator]),
        Schema.Field("fractional", zio.flow.remote.numeric.Fractional.schema),
        (op: FractionalPredicateOperator, n: zio.flow.remote.numeric.Fractional[Any]) => FractionalPredicate(op, n),
        _.operator,
        _.fractional
      ),
      _.asInstanceOf[FractionalPredicate[Any]]
    )

  private val charPredicateCase: Schema.Case[CharPredicate, UnaryOperators[Any, Any]] =
    Schema.Case(
      "CharPredicate",
      Schema.CaseClass1(
        Schema.Field("operator", Schema[CharPredicateOperator]),
        (op: CharPredicateOperator) => CharPredicate(op),
        _.operator
      ),
      _.asInstanceOf[CharPredicate]
    )

  private val conversionCase: Schema.Case[Conversion[Any, Any], UnaryOperators[Any, Any]] =
    Schema.Case(
      "Conversion",
      Schema.CaseClass1(
        Schema.Field("conversion", RemoteConversions.schemaAny),
        (conversion: RemoteConversions[Any, Any]) => Conversion(conversion),
        _.conversion
      ),
      _.asInstanceOf[Conversion[Any, Any]]
    )

  def schema[In, Out]: Schema[UnaryOperators[In, Out]] = schemaAny.asInstanceOf[Schema[UnaryOperators[In, Out]]]
  val schemaAny: Schema[UnaryOperators[Any, Any]] =
    Schema.EnumN(
      CaseSet
        .Cons(
          numericCase,
          CaseSet.Empty[UnaryOperators[Any, Any]]()
        )
        .:+:(integralCase)
        .:+:(fractionalCase)
        .:+:(numericPredicateCase)
        .:+:(fractionalPredicateCase)
        .:+:(charPredicateCase)
        .:+:(conversionCase)
    )
}
