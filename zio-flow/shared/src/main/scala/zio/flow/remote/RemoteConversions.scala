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

import zio.flow.remote.numeric._
import zio.flow.remote.text.{CharConversion, CharToCodeConversion}
import zio.flow.serialization.FlowSchemaAst
import zio.schema.{CaseSet, Schema, TypeId}

sealed trait RemoteConversions[In, Out] {
  val inputSchema: Schema[In]
  val outputSchema: Schema[Out]

  def apply(value: In): Out
}

object RemoteConversions {
  final case class NumericToInt[A](numeric: Numeric[A]) extends RemoteConversions[A, Int] {
    override val inputSchema: Schema[A]    = numeric.schema
    override val outputSchema: Schema[Int] = Schema[Int]

    override def apply(value: A): Int = numeric.toInt(value)
  }

  final case class NumericToShort[A](numeric: Numeric[A]) extends RemoteConversions[A, Short] {
    override val inputSchema: Schema[A]      = numeric.schema
    override val outputSchema: Schema[Short] = Schema[Short]

    override def apply(value: A): Short = numeric.toShort(value)
  }

  final case class NumericToLong[A](numeric: Numeric[A]) extends RemoteConversions[A, Long] {
    override val inputSchema: Schema[A]     = numeric.schema
    override val outputSchema: Schema[Long] = Schema[Long]

    override def apply(value: A): Long = numeric.toLong(value)
  }

  final case class NumericToFloat[A](numeric: Numeric[A]) extends RemoteConversions[A, Float] {
    override val inputSchema: Schema[A]      = numeric.schema
    override val outputSchema: Schema[Float] = Schema[Float]

    override def apply(value: A): Float = numeric.toFloat(value)
  }

  final case class NumericToDouble[A](numeric: Numeric[A]) extends RemoteConversions[A, Double] {
    override val inputSchema: Schema[A]       = numeric.schema
    override val outputSchema: Schema[Double] = Schema[Double]

    override def apply(value: A): Double = numeric.toDouble(value)
  }

  final case class NumericToBigDecimal[A](numeric: Numeric[A]) extends RemoteConversions[A, BigDecimal] {
    override val inputSchema: Schema[A]           = numeric.schema
    override val outputSchema: Schema[BigDecimal] = Schema[BigDecimal]

    override def apply(value: A): BigDecimal = numeric.toBigDecimal(value)
  }

  final case class NumericToBinaryString[A](bitwise: Integral[A]) extends RemoteConversions[A, String] {
    override val inputSchema: Schema[A]       = bitwise.schema
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: A): String = bitwise.toBinaryString(value)
  }

  final case class NumericToHexString[A](bitwise: Integral[A]) extends RemoteConversions[A, String] {
    override val inputSchema: Schema[A]       = bitwise.schema
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: A): String = bitwise.toHexString(value)
  }

  final case class NumericToOctalString[A](bitwise: Integral[A]) extends RemoteConversions[A, String] {
    override val inputSchema: Schema[A]       = bitwise.schema
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: A): String = bitwise.toOctalString(value)
  }

  final case class ToString[A]()(implicit schema: Schema[A]) extends RemoteConversions[A, String] {
    override val inputSchema: Schema[A]       = Schema[A]
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: A): String = value.toString
  }

  final case class FractionalGetExponent[A](fractional: Fractional[A]) extends RemoteConversions[A, Int] {
    override val inputSchema: Schema[A]    = fractional.schema
    override val outputSchema: Schema[Int] = Schema[Int]

    override def apply(value: A): Int =
      fractional.getExponent(value)
  }

  final case class CharToCode(operator: CharToCodeConversion) extends RemoteConversions[Char, Int] {
    override val inputSchema: Schema[Char] = Schema[Char]
    override val outputSchema: Schema[Int] = Schema[Int]

    override def apply(value: Char): Int =
      CharToCodeConversion.evaluate(value, operator)
  }

  final case class CharToChar(operator: CharConversion) extends RemoteConversions[Char, Char] {
    override val inputSchema: Schema[Char]  = Schema[Char]
    override val outputSchema: Schema[Char] = Schema[Char]

    override def apply(value: Char): Char =
      CharConversion.evaluate(value, operator)
  }

  final case class StringToNumeric[A](numeric: Numeric[A]) extends RemoteConversions[String, Option[A]] {
    override val inputSchema: Schema[String]     = Schema[String]
    override val outputSchema: Schema[Option[A]] = Schema.option(numeric.schema)

    override def apply(value: String): Option[A] = numeric.parse(value)
  }

  private val numericToIntCase: Schema.Case[NumericToInt[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToInt",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToInt"),
        Schema.Field("numeric", Numeric.schema),
        (numeric: Numeric[Any]) => NumericToInt(numeric),
        _.numeric
      ),
      _.asInstanceOf[NumericToInt[Any]]
    )

  private val numericToShortCase: Schema.Case[NumericToShort[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToShort",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToShort"),
        Schema.Field("numeric", Numeric.schema),
        (numeric: Numeric[Any]) => NumericToShort(numeric),
        _.numeric
      ),
      _.asInstanceOf[NumericToShort[Any]]
    )

  private val numericToLongCase: Schema.Case[NumericToLong[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToLong",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToLong"),
        Schema.Field("numeric", Numeric.schema),
        (numeric: Numeric[Any]) => NumericToLong(numeric),
        _.numeric
      ),
      _.asInstanceOf[NumericToLong[Any]]
    )

  private val numericToFloatCase: Schema.Case[NumericToFloat[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToFloat",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToFloat"),
        Schema.Field("numeric", Numeric.schema),
        (numeric: Numeric[Any]) => NumericToFloat(numeric),
        _.numeric
      ),
      _.asInstanceOf[NumericToFloat[Any]]
    )

  private val numericToDoubleCase: Schema.Case[NumericToDouble[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToDouble",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToDouble"),
        Schema.Field("numeric", Numeric.schema),
        (numeric: Numeric[Any]) => NumericToDouble(numeric),
        _.numeric
      ),
      _.asInstanceOf[NumericToDouble[Any]]
    )

  private val numericToBigDecimalCase: Schema.Case[NumericToBigDecimal[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToBigDecimal",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToBigDecimal"),
        Schema.Field("numeric", Numeric.schema),
        (numeric: Numeric[Any]) => NumericToBigDecimal(numeric),
        _.numeric
      ),
      _.asInstanceOf[NumericToBigDecimal[Any]]
    )

  private val numericToBinaryStringCase: Schema.Case[NumericToBinaryString[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToBinaryString",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToBinaryString"),
        Schema.Field("bitwise", Integral.schema),
        (numeric: Integral[Any]) => NumericToBinaryString(numeric),
        _.bitwise
      ),
      _.asInstanceOf[NumericToBinaryString[Any]]
    )

  private val numericToHexStringCase: Schema.Case[NumericToHexString[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToHexString",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToHexString"),
        Schema.Field("bitwise", Integral.schema),
        (numeric: Integral[Any]) => NumericToHexString(numeric),
        _.bitwise
      ),
      _.asInstanceOf[NumericToHexString[Any]]
    )

  private val numericToOctalStringCase: Schema.Case[NumericToOctalString[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "NumericToOctalString",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToOctalString"),
        Schema.Field("bitwise", Integral.schema),
        (numeric: Integral[Any]) => NumericToOctalString(numeric),
        _.bitwise
      ),
      _.asInstanceOf[NumericToOctalString[Any]]
    )

  private val toStringCase: Schema.Case[ToString[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "ToString",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.ToString"),
        Schema.Field("schema", Schema[FlowSchemaAst]),
        (schemaAst: FlowSchemaAst) => ToString()(schemaAst.toSchema[Any]),
        conv => FlowSchemaAst.fromSchema(conv.inputSchema)
      ),
      _.asInstanceOf[ToString[Any]]
    )

  private val fractionalGetExponentCase: Schema.Case[FractionalGetExponent[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "FractionalGetExponent",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.GetExponent"),
        Schema.Field("fractional", Fractional.schema),
        (fractional: Fractional[Any]) => FractionalGetExponent(fractional),
        _.fractional
      ),
      _.asInstanceOf[FractionalGetExponent[Any]]
    )

  private val charToCodeCase: Schema.Case[CharToCode, RemoteConversions[Any, Any]] =
    Schema.Case(
      "CharToCode",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.CharToCode"),
        Schema.Field("operator", CharToCodeConversion.schema),
        CharToCode.apply,
        _.operator
      ),
      _.asInstanceOf[CharToCode]
    )

  private val charToCharCase: Schema.Case[CharToChar, RemoteConversions[Any, Any]] =
    Schema.Case(
      "CharToChar",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.CharToChar"),
        Schema.Field("operator", CharConversion.schema),
        CharToChar.apply,
        _.operator
      ),
      _.asInstanceOf[CharToChar]
    )

  private val stringToNumericCase: Schema.Case[StringToNumeric[Any], RemoteConversions[Any, Any]] =
    Schema.Case(
      "StringToNumeric",
      Schema.CaseClass1(
        TypeId.parse("zio.flow.remote.RemoteConversions.StringToNumeric"),
        Schema.Field("numeric", Numeric.schema),
        (numeric: Numeric[Any]) => StringToNumeric(numeric),
        _.numeric
      ),
      _.asInstanceOf[StringToNumeric[Any]]
    )

  def schema[In, Out]: Schema[RemoteConversions[In, Out]] = schemaAny.asInstanceOf[Schema[RemoteConversions[In, Out]]]

  val schemaAny: Schema[RemoteConversions[Any, Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.RemoteConversions"),
      CaseSet
        .Cons(
          numericToIntCase,
          CaseSet.Empty[RemoteConversions[Any, Any]]()
        )
        .:+:(numericToShortCase)
        .:+:(numericToLongCase)
        .:+:(numericToFloatCase)
        .:+:(numericToDoubleCase)
        .:+:(numericToBigDecimalCase)
        .:+:(numericToBinaryStringCase)
        .:+:(numericToHexStringCase)
        .:+:(numericToOctalStringCase)
        .:+:(toStringCase)
        .:+:(fractionalGetExponentCase)
        .:+:(charToCodeCase)
        .:+:(charToCharCase)
        .:+:(stringToNumericCase)
    )
}
