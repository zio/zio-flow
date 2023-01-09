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

import zio.flow.remote.boolean.UnaryBooleanOperator
import zio.flow.remote.numeric.{
  FractionalPredicateOperator,
  NumericPredicateOperator,
  UnaryFractionalOperator,
  UnaryIntegralOperator,
  UnaryNumericOperator
}
import zio.flow.remote.text.{CharPredicateOperator, UnaryStringOperator}
import zio.schema.{CaseSet, Schema, TypeId}

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

  def apply(operator: UnaryStringOperator): Str =
    Str(operator)

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

  final case class Bool(operator: UnaryBooleanOperator) extends UnaryOperators[Boolean, Boolean] {
    override val inputSchema: Schema[Boolean]  = Schema[Boolean]
    override val outputSchema: Schema[Boolean] = Schema[Boolean]

    override def apply(value: Boolean): Boolean =
      UnaryBooleanOperator.evaluate(value, operator)
  }

  final case class Str(operator: UnaryStringOperator) extends UnaryOperators[String, String] {
    override val inputSchema: Schema[String]  = Schema[String]
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: String): String =
      UnaryStringOperator.evaluate(value, operator)
  }

  final case class Conversion[In, Out](
    conversion: RemoteConversions[In, Out]
  ) extends UnaryOperators[In, Out] {
    override val inputSchema: Schema[In]   = conversion.inputSchema
    override val outputSchema: Schema[Out] = conversion.outputSchema

    override def apply(value: In): Out = conversion(value)
  }

  private val numericCase: Schema.Case[UnaryOperators[Any, Any], Numeric[Any]] =
    Schema.Case(
      "Numeric",
      Schema.CaseClass2[UnaryNumericOperator, zio.flow.remote.numeric.Numeric[Any], Numeric[Any]](
        TypeId.parse("zio.flow.remote.UnaryOperators.Numeric"),
        Schema
          .Field("operator", Schema[UnaryNumericOperator], get0 = _.operator, set0 = (o, v) => o.copy(operator = v)),
        Schema.Field(
          "numeric",
          zio.flow.remote.numeric.Numeric.schema,
          get0 = _.numeric,
          set0 = (o, v) => o.copy(numeric = v)
        ),
        (op: UnaryNumericOperator, n: zio.flow.remote.numeric.Numeric[Any]) => Numeric(op, n)
      ),
      _.asInstanceOf[Numeric[Any]],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[Numeric[Any]]
    )

  private val integralCase: Schema.Case[UnaryOperators[Any, Any], Integral[Any]] =
    Schema.Case(
      "Integral",
      Schema.CaseClass2[UnaryIntegralOperator, zio.flow.remote.numeric.Integral[Any], Integral[Any]](
        TypeId.parse("zio.flow.remote.UnaryOperators.Integral"),
        Schema
          .Field("operator", Schema[UnaryIntegralOperator], get0 = _.operator, set0 = (o, v) => o.copy(operator = v)),
        Schema.Field(
          "integral",
          zio.flow.remote.numeric.Integral.schema,
          get0 = _.integral,
          set0 = (o, v) => o.copy(integral = v)
        ),
        (op: UnaryIntegralOperator, b: zio.flow.remote.numeric.Integral[Any]) => Integral(op, b)
      ),
      _.asInstanceOf[Integral[Any]],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[Integral[Any]]
    )

  private val fractionalCase: Schema.Case[UnaryOperators[Any, Any], Fractional[Any]] =
    Schema.Case(
      "Fractional",
      Schema.CaseClass2[UnaryFractionalOperator, zio.flow.remote.numeric.Fractional[Any], Fractional[Any]](
        TypeId.parse("zio.flow.remote.UnaryOperators.Fractional"),
        Schema
          .Field("operator", Schema[UnaryFractionalOperator], get0 = _.operator, set0 = (o, v) => o.copy(operator = v)),
        Schema.Field(
          "fractional",
          zio.flow.remote.numeric.Fractional.schema,
          get0 = _.fractional,
          set0 = (o, v) => o.copy(fractional = v)
        ),
        (op: UnaryFractionalOperator, f: zio.flow.remote.numeric.Fractional[Any]) => Fractional(op, f)
      ),
      _.asInstanceOf[Fractional[Any]],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[Fractional[Any]]
    )

  private val numericPredicateCase: Schema.Case[UnaryOperators[Any, Any], NumericPredicate[Any]] =
    Schema.Case(
      "NumericPredicate",
      Schema.CaseClass2[NumericPredicateOperator, zio.flow.remote.numeric.Numeric[Any], NumericPredicate[Any]](
        TypeId.parse("zio.flow.remote.UnaryOperators.NumericPredicate"),
        Schema.Field(
          "operator",
          Schema[NumericPredicateOperator],
          get0 = _.operator,
          set0 = (o, v) => o.copy(operator = v)
        ),
        Schema.Field(
          "numeric",
          zio.flow.remote.numeric.Numeric.schema,
          get0 = _.numeric,
          set0 = (o, v) => o.copy(numeric = v)
        ),
        (op: NumericPredicateOperator, n: zio.flow.remote.numeric.Numeric[Any]) => NumericPredicate(op, n)
      ),
      _.asInstanceOf[NumericPredicate[Any]],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[NumericPredicate[Any]]
    )

  private val fractionalPredicateCase: Schema.Case[UnaryOperators[Any, Any], FractionalPredicate[Any]] =
    Schema.Case(
      "FractionalPredicate",
      Schema.CaseClass2[FractionalPredicateOperator, zio.flow.remote.numeric.Fractional[Any], FractionalPredicate[Any]](
        TypeId.parse("zio.flow.remote.UnaryOperators.FractionalPredicate"),
        Schema.Field(
          "operator",
          Schema[FractionalPredicateOperator],
          get0 = _.operator,
          set0 = (o, v) => o.copy(operator = v)
        ),
        Schema.Field(
          "fractional",
          zio.flow.remote.numeric.Fractional.schema,
          get0 = _.fractional,
          set0 = (o, v) => o.copy(fractional = v)
        ),
        (op: FractionalPredicateOperator, n: zio.flow.remote.numeric.Fractional[Any]) => FractionalPredicate(op, n)
      ),
      _.asInstanceOf[FractionalPredicate[Any]],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[FractionalPredicate[Any]]
    )

  private val charPredicateCase: Schema.Case[UnaryOperators[Any, Any], CharPredicate] =
    Schema.Case(
      "CharPredicate",
      Schema.CaseClass1[CharPredicateOperator, CharPredicate](
        TypeId.parse("zio.flow.remote.UnaryOperators.CharPredicate"),
        Schema
          .Field("operator", Schema[CharPredicateOperator], get0 = _.operator, set0 = (o, v) => o.copy(operator = v)),
        (op: CharPredicateOperator) => CharPredicate(op)
      ),
      _.asInstanceOf[CharPredicate],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[CharPredicate]
    )

  private val boolCase: Schema.Case[UnaryOperators[Any, Any], Bool] =
    Schema.Case(
      "Bool",
      Schema.CaseClass1[UnaryBooleanOperator, Bool](
        TypeId.parse("zio.flow.remote.UnaryOperators.Bool"),
        Schema
          .Field("operator", Schema[UnaryBooleanOperator], get0 = _.operator, set0 = (o, v) => o.copy(operator = v)),
        (op: UnaryBooleanOperator) => Bool(op)
      ),
      _.asInstanceOf[Bool],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[Bool]
    )

  private val strCase: Schema.Case[UnaryOperators[Any, Any], Str] =
    Schema.Case(
      "Str",
      Schema.CaseClass1[UnaryStringOperator, Str](
        TypeId.parse("zio.flow.remote.UnaryOperators.Str"),
        Schema.Field("operator", Schema[UnaryStringOperator], get0 = _.operator, set0 = (o, v) => o.copy(operator = v)),
        (op: UnaryStringOperator) => Str(op)
      ),
      _.asInstanceOf[Str],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[Str]
    )

  private val conversionCase: Schema.Case[UnaryOperators[Any, Any], Conversion[Any, Any]] =
    Schema.Case(
      "Conversion",
      Schema.CaseClass1[RemoteConversions[Any, Any], Conversion[Any, Any]](
        TypeId.parse("zio.flow.remote.UnaryOperators.Conversion"),
        Schema.Field(
          "conversion",
          RemoteConversions.schemaAny,
          get0 = _.conversion,
          set0 = (o, v) => o.copy(conversion = v)
        ),
        (conversion: RemoteConversions[Any, Any]) => Conversion(conversion)
      ),
      _.asInstanceOf[Conversion[Any, Any]],
      _.asInstanceOf[UnaryOperators[Any, Any]],
      _.isInstanceOf[Conversion[Any, Any]]
    )

  def schema[In, Out]: Schema[UnaryOperators[In, Out]] = schemaAny.asInstanceOf[Schema[UnaryOperators[In, Out]]]

  lazy val schemaAny: Schema[UnaryOperators[Any, Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.UnaryOperators"),
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
        .:+:(boolCase)
        .:+:(strCase)
        .:+:(conversionCase)
    )
}
