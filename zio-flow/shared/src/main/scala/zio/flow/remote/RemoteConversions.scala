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

import zio.Duration
import zio.flow.{Instant, regexSchema}
import zio.flow.remote.numeric._
import zio.flow.remote.text.{CharConversion, CharToCodeConversion}
import zio.flow.serialization.FlowSchemaAst
import zio.schema.{CaseSet, Schema, TypeId}

import java.time.{OffsetDateTime, ZoneOffset}
import scala.util.matching.Regex

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

  final case class NumericToChar[A](numeric: Numeric[A]) extends RemoteConversions[A, Char] {
    override val inputSchema: Schema[A]     = numeric.schema
    override val outputSchema: Schema[Char] = Schema[Char]

    override def apply(value: A): Char = numeric.toChar(value)
  }

  final case class NumericToByte[A](numeric: Numeric[A]) extends RemoteConversions[A, Byte] {
    override val inputSchema: Schema[A]     = numeric.schema
    override val outputSchema: Schema[Byte] = Schema[Byte]

    override def apply(value: A): Byte = numeric.toByte(value)
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

  final case class NumericToBinaryString[A](integral: Integral[A]) extends RemoteConversions[A, String] {
    override val inputSchema: Schema[A]       = integral.schema
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: A): String = integral.toBinaryString(value)
  }

  final case class NumericToHexString[A](integral: Integral[A]) extends RemoteConversions[A, String] {
    override val inputSchema: Schema[A]       = integral.schema
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: A): String = integral.toHexString(value)
  }

  final case class NumericToOctalString[A](integral: Integral[A]) extends RemoteConversions[A, String] {
    override val inputSchema: Schema[A]       = integral.schema
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: A): String = integral.toOctalString(value)
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

  case object StringToDuration extends RemoteConversions[String, Duration] {
    override val inputSchema: Schema[String]    = Schema[String]
    override val outputSchema: Schema[Duration] = Schema[Duration]

    override def apply(value: String): Duration =
      java.time.Duration.parse(value)
  }

  case object BigDecimalToDuration extends RemoteConversions[BigDecimal, Duration] {
    override val inputSchema: Schema[BigDecimal] = Schema[BigDecimal]
    override val outputSchema: Schema[Duration]  = Schema[Duration]

    private val oneBillion = new java.math.BigDecimal(1000000000L)

    override def apply(value: BigDecimal): Duration = {
      val bd      = value.bigDecimal
      val seconds = bd.longValue()
      val nanos   = bd.subtract(new java.math.BigDecimal(seconds)).multiply(oneBillion).intValue()
      java.time.Duration.ofSeconds(seconds, nanos.toLong)
    }
  }

  case object DurationToTuple extends RemoteConversions[Duration, (Long, Int)] {
    override val inputSchema: Schema[Duration]     = Schema[Duration]
    override val outputSchema: Schema[(Long, Int)] = Schema.tuple2[Long, Int]

    override def apply(value: Duration): (Long, Int) =
      (value.getSeconds, value.getNano)
  }

  case object StringToInstant extends RemoteConversions[String, Instant] {
    override val inputSchema: Schema[String]   = Schema[String]
    override val outputSchema: Schema[Instant] = Schema[Instant]

    override def apply(value: String): Instant =
      java.time.Instant.parse(value)
  }

  case object TupleToInstant extends RemoteConversions[(Long, Int), Instant] {
    override val inputSchema: Schema[(Long, Int)] = Schema.tuple2[Long, Int]
    override val outputSchema: Schema[Instant]    = Schema[Instant]

    override def apply(value: (Long, Int)): Instant =
      java.time.Instant.ofEpochSecond(value._1, value._2.toLong)
  }

  case object InstantToTuple extends RemoteConversions[Instant, (Long, Int)] {
    override val inputSchema: Schema[Instant]      = Schema[Instant]
    override val outputSchema: Schema[(Long, Int)] = Schema.tuple2[Long, Int]

    override def apply(value: Instant): (Long, Int) =
      (value.getEpochSecond, value.getNano)
  }

  case object StringToRegex extends RemoteConversions[String, Regex] {
    override val inputSchema: Schema[String] = Schema[String]
    override val outputSchema: Schema[Regex] = Schema[Regex]

    override def apply(value: String): Regex =
      value.r
  }

  case object RegexToString extends RemoteConversions[Regex, String] {
    override val inputSchema: Schema[Regex]   = Schema[Regex]
    override val outputSchema: Schema[String] = Schema[String]

    override def apply(value: Regex): String =
      value.regex
  }

  case object OffsetDateTimeToInstant extends RemoteConversions[OffsetDateTime, Instant] {
    override val inputSchema: Schema[OffsetDateTime] = Schema[OffsetDateTime]
    override val outputSchema: Schema[Instant]       = Schema[Instant]

    override def apply(value: OffsetDateTime): Instant =
      value.toInstant
  }

  case object OffsetDateTimeToTuple
      extends RemoteConversions[OffsetDateTime, (Int, Int, Int, Int, Int, Int, Int, ZoneOffset)] {
    override val inputSchema: Schema[OffsetDateTime] = Schema[OffsetDateTime]
    override val outputSchema: Schema[(Int, Int, Int, Int, Int, Int, Int, ZoneOffset)] =
      Schema.tuple8[Int, Int, Int, Int, Int, Int, Int, ZoneOffset]

    override def apply(value: OffsetDateTime): (Int, Int, Int, Int, Int, Int, Int, ZoneOffset) =
      (
        value.getYear,
        value.getMonthValue,
        value.getDayOfMonth,
        value.getHour,
        value.getMinute,
        value.getSecond,
        value.getNano,
        value.getOffset
      )
  }

  case object TupleToOffsetDateTime
      extends RemoteConversions[(Int, Int, Int, Int, Int, Int, Int, ZoneOffset), OffsetDateTime] {
    override val inputSchema: Schema[(Int, Int, Int, Int, Int, Int, Int, ZoneOffset)] =
      Schema.tuple8[Int, Int, Int, Int, Int, Int, Int, ZoneOffset]
    override val outputSchema: Schema[OffsetDateTime] = Schema[OffsetDateTime]

    override def apply(value: (Int, Int, Int, Int, Int, Int, Int, ZoneOffset)): OffsetDateTime =
      OffsetDateTime.of(value._1, value._2, value._3, value._4, value._5, value._6, value._7, value._8)
  }

  private val numericToIntCase: Schema.Case[RemoteConversions[Any, Any], NumericToInt[Any]] =
    Schema.Case(
      "NumericToInt",
      Schema.CaseClass1[Numeric[Any], NumericToInt[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToInt"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToInt(numeric)
      ),
      _.asInstanceOf[NumericToInt[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToInt[Any]]
    )

  private val numericToCharCase: Schema.Case[RemoteConversions[Any, Any], NumericToChar[Any]] =
    Schema.Case(
      "NumericToChar",
      Schema.CaseClass1[Numeric[Any], NumericToChar[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToChar"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToChar(numeric)
      ),
      _.asInstanceOf[NumericToChar[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToChar[Any]]
    )

  private val numericToByteCase: Schema.Case[RemoteConversions[Any, Any], NumericToByte[Any]] =
    Schema.Case(
      "NumericToByte",
      Schema.CaseClass1[Numeric[Any], NumericToByte[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToByte"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToByte(numeric)
      ),
      _.asInstanceOf[NumericToByte[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToByte[Any]]
    )

  private val numericToShortCase: Schema.Case[RemoteConversions[Any, Any], NumericToShort[Any]] =
    Schema.Case(
      "NumericToShort",
      Schema.CaseClass1[Numeric[Any], NumericToShort[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToShort"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToShort(numeric)
      ),
      _.asInstanceOf[NumericToShort[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToShort[Any]]
    )

  private val numericToLongCase: Schema.Case[RemoteConversions[Any, Any], NumericToLong[Any]] =
    Schema.Case(
      "NumericToLong",
      Schema.CaseClass1[Numeric[Any], NumericToLong[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToLong"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToLong(numeric)
      ),
      _.asInstanceOf[NumericToLong[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToLong[Any]]
    )

  private val numericToFloatCase: Schema.Case[RemoteConversions[Any, Any], NumericToFloat[Any]] =
    Schema.Case(
      "NumericToFloat",
      Schema.CaseClass1[Numeric[Any], NumericToFloat[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToFloat"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToFloat(numeric)
      ),
      _.asInstanceOf[NumericToFloat[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToFloat[Any]]
    )

  private val numericToDoubleCase: Schema.Case[RemoteConversions[Any, Any], NumericToDouble[Any]] =
    Schema.Case(
      "NumericToDouble",
      Schema.CaseClass1[Numeric[Any], NumericToDouble[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToDouble"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToDouble(numeric)
      ),
      _.asInstanceOf[NumericToDouble[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToDouble[Any]]
    )

  private val numericToBigDecimalCase: Schema.Case[RemoteConversions[Any, Any], NumericToBigDecimal[Any]] =
    Schema.Case(
      "NumericToBigDecimal",
      Schema.CaseClass1[Numeric[Any], NumericToBigDecimal[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToBigDecimal"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => NumericToBigDecimal(numeric)
      ),
      _.asInstanceOf[NumericToBigDecimal[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToBigDecimal[Any]]
    )

  private val numericToBinaryStringCase: Schema.Case[RemoteConversions[Any, Any], NumericToBinaryString[Any]] =
    Schema.Case(
      "NumericToBinaryString",
      Schema.CaseClass1[Integral[Any], NumericToBinaryString[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToBinaryString"),
        Schema.Field("bitwise", Integral.schema, get0 = _.integral, set0 = (a, b) => a.copy(integral = b)),
        (numeric: Integral[Any]) => NumericToBinaryString(numeric)
      ),
      _.asInstanceOf[NumericToBinaryString[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToBinaryString[Any]]
    )

  private val numericToHexStringCase: Schema.Case[RemoteConversions[Any, Any], NumericToHexString[Any]] =
    Schema.Case(
      "NumericToHexString",
      Schema.CaseClass1[Integral[Any], NumericToHexString[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToHexString"),
        Schema.Field("bitwise", Integral.schema, get0 = _.integral, set0 = (a, b) => a.copy(integral = b)),
        (numeric: Integral[Any]) => NumericToHexString(numeric)
      ),
      _.asInstanceOf[NumericToHexString[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToHexString[Any]]
    )

  private val numericToOctalStringCase: Schema.Case[RemoteConversions[Any, Any], NumericToOctalString[Any]] =
    Schema.Case(
      "NumericToOctalString",
      Schema.CaseClass1[Integral[Any], NumericToOctalString[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.NumericToOctalString"),
        Schema.Field("bitwise", Integral.schema, get0 = _.integral, set0 = (a, b) => a.copy(integral = b)),
        (numeric: Integral[Any]) => NumericToOctalString(numeric)
      ),
      _.asInstanceOf[NumericToOctalString[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[NumericToOctalString[Any]]
    )

  private val toStringCase: Schema.Case[RemoteConversions[Any, Any], ToString[Any]] =
    Schema.Case(
      "ToString",
      Schema.CaseClass1[FlowSchemaAst, ToString[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.ToString"),
        Schema.Field(
          "schema",
          Schema[FlowSchemaAst],
          get0 = conv => FlowSchemaAst.fromSchema(conv.inputSchema),
          set0 = (_: ToString[Any], b: FlowSchemaAst) => ToString()(b.toSchema.asInstanceOf[Schema[Any]])
        ),
        (schemaAst: FlowSchemaAst) => ToString()(schemaAst.toSchema.asInstanceOf[Schema[Any]])
      ),
      _.asInstanceOf[ToString[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[ToString[Any]]
    )

  private val fractionalGetExponentCase: Schema.Case[RemoteConversions[Any, Any], FractionalGetExponent[Any]] =
    Schema.Case(
      "FractionalGetExponent",
      Schema.CaseClass1[Fractional[Any], FractionalGetExponent[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.GetExponent"),
        Schema.Field("fractional", Fractional.schema, get0 = _.fractional, set0 = (a, b) => a.copy(fractional = b)),
        (fractional: Fractional[Any]) => FractionalGetExponent(fractional)
      ),
      _.asInstanceOf[FractionalGetExponent[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[FractionalGetExponent[Any]]
    )

  private val charToCodeCase: Schema.Case[RemoteConversions[Any, Any], CharToCode] =
    Schema.Case(
      "CharToCode",
      Schema.CaseClass1[CharToCodeConversion, CharToCode](
        TypeId.parse("zio.flow.remote.RemoteConversions.CharToCode"),
        Schema.Field("operator", CharToCodeConversion.schema, get0 = _.operator, set0 = (a, b) => a.copy(operator = b)),
        CharToCode.apply
      ),
      _.asInstanceOf[CharToCode],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[CharToCode]
    )

  private val charToCharCase: Schema.Case[RemoteConversions[Any, Any], CharToChar] =
    Schema.Case(
      "CharToChar",
      Schema.CaseClass1[CharConversion, CharToChar](
        TypeId.parse("zio.flow.remote.RemoteConversions.CharToChar"),
        Schema.Field("operator", CharConversion.schema, get0 = _.operator, set0 = (a, b) => a.copy(operator = b)),
        CharToChar.apply
      ),
      _.asInstanceOf[CharToChar],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[CharToChar]
    )

  private val stringToNumericCase: Schema.Case[RemoteConversions[Any, Any], StringToNumeric[Any]] =
    Schema.Case(
      "StringToNumeric",
      Schema.CaseClass1[Numeric[Any], StringToNumeric[Any]](
        TypeId.parse("zio.flow.remote.RemoteConversions.StringToNumeric"),
        Schema.Field("numeric", Numeric.schema, get0 = _.numeric, set0 = (a, b) => a.copy(numeric = b)),
        (numeric: Numeric[Any]) => StringToNumeric(numeric)
      ),
      _.asInstanceOf[StringToNumeric[Any]],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[StringToNumeric[_]]
    )

  private val stringToDurationCase: Schema.Case[RemoteConversions[Any, Any], StringToDuration.type] =
    Schema.Case(
      "StringToDuration",
      Schema.singleton(StringToDuration),
      _.asInstanceOf[StringToDuration.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[StringToDuration.type]
    )

  private val bigDecimalToDurationCase: Schema.Case[RemoteConversions[Any, Any], BigDecimalToDuration.type] =
    Schema.Case(
      "BigDecimalToDuration",
      Schema.singleton(BigDecimalToDuration),
      _.asInstanceOf[BigDecimalToDuration.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[BigDecimalToDuration.type]
    )

  private val durationToTupleCase: Schema.Case[RemoteConversions[Any, Any], DurationToTuple.type] =
    Schema.Case(
      "DurationToTuple",
      Schema.singleton(DurationToTuple),
      _.asInstanceOf[DurationToTuple.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[DurationToTuple.type]
    )

  private val stringToInstantCase: Schema.Case[RemoteConversions[Any, Any], StringToInstant.type] =
    Schema.Case(
      "StringToInstant",
      Schema.singleton(StringToInstant),
      _.asInstanceOf[StringToInstant.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[StringToInstant.type]
    )

  private val tupleToInstant: Schema.Case[RemoteConversions[Any, Any], TupleToInstant.type] =
    Schema.Case(
      "TupleToInstant",
      Schema.singleton(TupleToInstant),
      _.asInstanceOf[TupleToInstant.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[TupleToInstant.type]
    )

  private val instantToTuple: Schema.Case[RemoteConversions[Any, Any], InstantToTuple.type] =
    Schema.Case(
      "InstantToTuple",
      Schema.singleton(InstantToTuple),
      _.asInstanceOf[InstantToTuple.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[InstantToTuple.type]
    )

  private val stringToRegex: Schema.Case[RemoteConversions[Any, Any], StringToRegex.type] =
    Schema.Case(
      "StringToRegex",
      Schema.singleton(StringToRegex),
      _.asInstanceOf[StringToRegex.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[StringToRegex.type]
    )

  private val regexToString: Schema.Case[RemoteConversions[Any, Any], RegexToString.type] =
    Schema.Case(
      "RegexToString",
      Schema.singleton(RegexToString),
      _.asInstanceOf[RegexToString.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[RegexToString.type]
    )

  private val offsetDateTimeToInstant: Schema.Case[RemoteConversions[Any, Any], OffsetDateTimeToInstant.type] =
    Schema.Case(
      "OffsetDateTimeToInstant",
      Schema.singleton(OffsetDateTimeToInstant),
      _.asInstanceOf[OffsetDateTimeToInstant.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[OffsetDateTimeToInstant.type]
    )

  private val offsetDateTimeToTuple: Schema.Case[RemoteConversions[Any, Any], OffsetDateTimeToTuple.type] =
    Schema.Case(
      "OffsetDateTimeToTuple",
      Schema.singleton(OffsetDateTimeToTuple),
      _.asInstanceOf[OffsetDateTimeToTuple.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[OffsetDateTimeToTuple.type]
    )

  private val tupleToOffsetDateTime: Schema.Case[RemoteConversions[Any, Any], TupleToOffsetDateTime.type] =
    Schema.Case(
      "TupleToOffsetDateTime",
      Schema.singleton(TupleToOffsetDateTime),
      _.asInstanceOf[TupleToOffsetDateTime.type],
      _.asInstanceOf[RemoteConversions[Any, Any]],
      _.isInstanceOf[TupleToOffsetDateTime.type]
    )

  def schema[In, Out]: Schema[RemoteConversions[In, Out]] = schemaAny.asInstanceOf[Schema[RemoteConversions[In, Out]]]

  lazy val schemaAny: Schema[RemoteConversions[Any, Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.RemoteConversions"),
      CaseSet
        .Cons(
          numericToIntCase,
          CaseSet.Empty[RemoteConversions[Any, Any]]()
        )
        .:+:(numericToCharCase)
        .:+:(numericToByteCase)
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
        .:+:(stringToDurationCase)
        .:+:(bigDecimalToDurationCase)
        .:+:(durationToTupleCase)
        .:+:(stringToInstantCase)
        .:+:(tupleToInstant)
        .:+:(instantToTuple)
        .:+:(stringToRegex)
        .:+:(regexToString)
        .:+:(offsetDateTimeToInstant)
        .:+:(offsetDateTimeToTuple)
        .:+:(tupleToOffsetDateTime)
    )
}
