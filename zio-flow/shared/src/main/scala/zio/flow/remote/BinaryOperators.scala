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

import zio.flow.regexSchema
import zio.flow.remote.boolean.BinaryBooleanOperator
import zio.flow.remote.numeric.{BinaryFractionalOperator, BinaryIntegralOperator, BinaryNumericOperator}
import zio.flow.serialization.FlowSchemaAst
import zio.schema._

import scala.util.matching.Regex

sealed trait BinaryOperators[In1, In2, Out] {
  type Output = Out

  val inputSchema1: Schema[In1]
  val inputSchema2: Schema[In2]
  val outputSchema: Schema[Out]

  def apply(left: In1, right: In2): Out
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
      extends BinaryOperators[A, A, A] {
    override val inputSchema1: Schema[A] = numeric.schema
    override val inputSchema2: Schema[A] = numeric.schema
    override val outputSchema: Schema[A] = numeric.schema

    override def apply(left: A, right: A): A =
      numeric.binary(operator, left, right)
  }

  final case class Fractional[A](operator: BinaryFractionalOperator, fractional: zio.flow.remote.numeric.Fractional[A])
      extends BinaryOperators[A, A, A] {
    override val inputSchema1: Schema[A] = fractional.schema
    override val inputSchema2: Schema[A] = fractional.schema
    override val outputSchema: Schema[A] = fractional.schema

    override def apply(left: A, right: A): A =
      fractional.binary(operator, left, right)
  }

  final case class Integral[A](operator: BinaryIntegralOperator, bitwise: zio.flow.remote.numeric.Integral[A])
      extends BinaryOperators[A, A, A] {
    override val inputSchema1: Schema[A] = bitwise.schema
    override val inputSchema2: Schema[A] = bitwise.schema
    override val outputSchema: Schema[A] = bitwise.schema

    override def apply(left: A, right: A): A =
      bitwise.binary(operator, left, right)
  }

  final case class LessThanEqual[A](schema: Schema[A]) extends BinaryOperators[A, A, Boolean] {
    override val inputSchema1: Schema[A]       = schema
    override val inputSchema2: Schema[A]       = schema
    override val outputSchema: Schema[Boolean] = Schema[Boolean]

    override def equals(obj: Any): Boolean =
      obj match {
        case lte: LessThanEqual[_] =>
          Schema.structureEquality.equal(schema, lte.schema)
        case _ =>
          false
      }

    override def apply(left: A, right: A): Boolean =
      schema.ordering.compare(left, right) <= 0
  }

  final case class Bool(operator: BinaryBooleanOperator) extends BinaryOperators[Boolean, Boolean, Boolean] {
    override val inputSchema1: Schema[Boolean] = Schema[Boolean]
    override val inputSchema2: Schema[Boolean] = Schema[Boolean]
    override val outputSchema: Schema[Boolean] = Schema[Boolean]

    override def apply(left: Boolean, right: Boolean): Boolean =
      BinaryBooleanOperator.evaluate(left, right, operator)
  }

  case object RegexUnapplySeq extends BinaryOperators[Regex, String, Option[List[String]]] {
    override val inputSchema1: Schema[Regex]                = Schema[Regex]
    override val inputSchema2: Schema[String]               = Schema[String]
    override val outputSchema: Schema[Option[List[String]]] = Schema[Option[List[String]]]

    override def apply(left: Regex, right: String): Option[List[String]] =
      left.unapplySeq(right)
  }

  case object RegexFindFirstIn extends BinaryOperators[Regex, String, Option[String]] {
    override val inputSchema1: Schema[Regex]          = Schema[Regex]
    override val inputSchema2: Schema[String]         = Schema[String]
    override val outputSchema: Schema[Option[String]] = Schema[Option[String]]

    override def apply(left: Regex, right: String): Option[String] =
      left.findFirstIn(right)
  }

  case object RegexMatches extends BinaryOperators[Regex, String, Boolean] {
    override val inputSchema1: Schema[Regex]   = Schema[Regex]
    override val inputSchema2: Schema[String]  = Schema[String]
    override val outputSchema: Schema[Boolean] = Schema[Boolean]

    override def apply(left: Regex, right: String): Boolean =
      right match {
        case left(_*) => true
        case _        => false
      }
  }

  case object RegexReplaceAllIn extends BinaryOperators[Regex, (String, String), String] {
    override val inputSchema1: Schema[Regex]            = Schema[Regex]
    override val inputSchema2: Schema[(String, String)] = Schema[(String, String)]
    override val outputSchema: Schema[String]           = Schema[String]

    override def apply(left: Regex, right: (String, String)): String =
      left.replaceAllIn(right._1, right._2)
  }

  case object RegexReplaceFirstIn extends BinaryOperators[Regex, (String, String), String] {
    override val inputSchema1: Schema[Regex]            = Schema[Regex]
    override val inputSchema2: Schema[(String, String)] = Schema[(String, String)]
    override val outputSchema: Schema[String]           = Schema[String]

    override def apply(left: Regex, right: (String, String)): String =
      left.replaceFirstIn(right._1, right._2)
  }

  case object RegexSplit extends BinaryOperators[Regex, String, List[String]] {
    override val inputSchema1: Schema[Regex]        = Schema[Regex]
    override val inputSchema2: Schema[String]       = Schema[String]
    override val outputSchema: Schema[List[String]] = Schema[List[String]]

    override def apply(left: Regex, right: String): List[String] =
      left.split(right).toList
  }

  private val numericCase: Schema.Case[Numeric[Any], BinaryOperators[Any, Any, Any]] =
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

  private val fractionalCase: Schema.Case[Fractional[Any], BinaryOperators[Any, Any, Any]] =
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

  private val integralCase: Schema.Case[Integral[Any], BinaryOperators[Any, Any, Any]] =
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

  private val lessThenEqualCase: Schema.Case[LessThanEqual[Any], BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "LessThanEqual",
      Schema.CaseClass1[FlowSchemaAst, LessThanEqual[Any]](
        TypeId.parse("zio.flow.remote.BinaryOperators.LessThanEqual"),
        Schema.Field("schema", FlowSchemaAst.schema),
        (ast: FlowSchemaAst) => LessThanEqual(ast.toSchema[Any]),
        lte => FlowSchemaAst.fromSchema(lte.schema)
      ),
      _.asInstanceOf[LessThanEqual[Any]]
    )

  private val boolCase: Schema.Case[Bool, BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "Bool",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.BinaryOperators.Bool"),
        Schema.Field("operator", Schema[BinaryBooleanOperator]),
        (op: BinaryBooleanOperator) => Bool(op),
        _.operator
      ),
      _.asInstanceOf[Bool]
    )

  private val regexUnapplySeqCase: Schema.Case[RegexUnapplySeq.type, BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "RegexUnapplySeq",
      Schema.singleton(RegexUnapplySeq),
      _.asInstanceOf[RegexUnapplySeq.type]
    )

  private val regexFindFirstIn: Schema.Case[RegexFindFirstIn.type, BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "RegexFindFirstIn",
      Schema.singleton(RegexFindFirstIn),
      _.asInstanceOf[RegexFindFirstIn.type]
    )

  private val regexMatches: Schema.Case[RegexMatches.type, BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "RegexMatches",
      Schema.singleton(RegexMatches),
      _.asInstanceOf[RegexMatches.type]
    )

  private val regexReplaceAllIn: Schema.Case[RegexReplaceAllIn.type, BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "RegexReplaceAllIn",
      Schema.singleton(RegexReplaceAllIn),
      _.asInstanceOf[RegexReplaceAllIn.type]
    )

  private val regexReplaceFirstIn: Schema.Case[RegexReplaceFirstIn.type, BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "RegexReplaceFirstIn",
      Schema.singleton(RegexReplaceFirstIn),
      _.asInstanceOf[RegexReplaceFirstIn.type]
    )

  private val regexSplit: Schema.Case[RegexSplit.type, BinaryOperators[Any, Any, Any]] =
    Schema.Case(
      "RegexSplit",
      Schema.singleton(RegexSplit),
      _.asInstanceOf[RegexSplit.type]
    )

  def schema[In1, In2, Out]: Schema[BinaryOperators[In1, In2, Out]] =
    schemaAny.asInstanceOf[Schema[BinaryOperators[In1, In2, Out]]]

  val schemaAny: Schema[BinaryOperators[Any, Any, Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.BinaryOperators"),
      CaseSet
        .Cons(
          numericCase,
          CaseSet.Empty[BinaryOperators[Any, Any, Any]]()
        )
        .:+:(fractionalCase)
        .:+:(integralCase)
        .:+:(lessThenEqualCase)
        .:+:(boolCase)
        .:+:(regexUnapplySeqCase)
        .:+:(regexFindFirstIn)
        .:+:(regexMatches)
        .:+:(regexReplaceAllIn)
        .:+:(regexReplaceFirstIn)
        .:+:(regexSplit)
    )
}
