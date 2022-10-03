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

package zio.flow.remote.numeric

import zio.flow.Remote
import zio.flow.remote.numeric.Numeric.NumericInt
import zio.schema.{CaseSet, Schema, TypeId}

import scala.annotation.nowarn
import scala.math.ScalaNumericAnyConversions
import scala.util.Try

sealed trait Numeric[A] {
  val schema: Schema[A]

  def conversions(value: A): ScalaNumericAnyConversions

  def fromLong(value: Long): Remote[A]

  def parse(s: String): Option[A]

  def add(left: A, right: A): A

  def sub(left: A, right: A): A

  def mul(left: A, right: A): A

  def div(left: A, right: A): A

  def mod(left: A, right: A): A

  def neg(value: A): A

  def abs(value: A): A

  def sign(value: A): A

  def min(left: A, right: A): A

  def max(left: A, right: A): A

  @nowarn def isValidLong(value: A): Boolean = false

  def toInt(value: A): Int

  def toShort(value: A): Short

  def toLong(value: A): Long

  def toFloat(value: A): Float

  def toDouble(value: A): Double

  def toBigDecimal(value: A): BigDecimal

  def unary(op: UnaryNumericOperator, value: A): A =
    op match {
      case UnaryNumericOperator.Neg  => neg(value)
      case UnaryNumericOperator.Abs  => abs(value)
      case UnaryNumericOperator.Sign => sign(value)

    }

  def binary(op: BinaryNumericOperator, left: A, right: A): A =
    op match {
      case BinaryNumericOperator.Add => add(left, right)
      case BinaryNumericOperator.Sub => sub(left, right)
      case BinaryNumericOperator.Mul => mul(left, right)
      case BinaryNumericOperator.Div => div(left, right)
      case BinaryNumericOperator.Mod => mod(left, right)
      case BinaryNumericOperator.Min => min(left, right)
      case BinaryNumericOperator.Max => max(left, right)
    }

  def predicate(op: NumericPredicateOperator, value: A): Boolean =
    op match {
      case NumericPredicateOperator.IsWhole      => conversions(value).isWhole
      case NumericPredicateOperator.IsValidInt   => conversions(value).isValidInt
      case NumericPredicateOperator.IsValidByte  => conversions(value).isValidByte
      case NumericPredicateOperator.IsValidChar  => conversions(value).isValidChar
      case NumericPredicateOperator.IsValidLong  => isValidLong(value)
      case NumericPredicateOperator.IsValidShort => conversions(value).isValidShort
    }
}

object Numeric extends NumericImplicits0 {
  trait NumericInt extends Numeric[Int] with Integral[Int] {
    override val schema: Schema[Int] = implicitly[Schema[Int]]

    override def conversions(value: Int): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[Int] = Remote(value.toInt)

    override def parse(s: String): Option[Int] =
      Try(s.toInt).toOption

    override def add(left: Int, right: Int): Int = left + right

    override def sub(left: Int, right: Int): Int = left - right

    override def mul(left: Int, right: Int): Int = left * right

    override def div(left: Int, right: Int): Int = left / right

    override def neg(value: Int): Int = -value

    override def bitwiseNegate(left: Int): Int = ~left

    override def mod(left: Int, right: Int): Int = left % right

    override def abs(value: Int): Int = value.abs

    override def sign(value: Int): Int = if (value == 0) 0 else if (value < 0) -1 else 1

    override def min(left: Int, right: Int): Int = left.min(right)

    override def max(left: Int, right: Int): Int = left.max(right)

    override def leftShift(left: Int, right: Int): Int = left << right

    override def rightShift(left: Int, right: Int): Int = left >> right

    override def unsignedRightShift(left: Int, right: Int): Int = left >>> right

    override def and(left: Int, right: Int): Int = left & right

    override def or(left: Int, right: Int): Int = left | right

    override def xor(left: Int, right: Int): Int = left ^ right

    override def toInt(value: Int): Int = value

    override def toShort(value: Int): Short = value.toShort

    override def toLong(value: Int): Long = value.toLong

    override def toFloat(value: Int): Float = value.toFloat

    override def toDouble(value: Int): Double = value.toDouble

    override def toBigDecimal(value: Int): BigDecimal = BigDecimal(value)

    override def toBinaryString(value: Int): String = value.toBinaryString

    override def toHexString(value: Int): String = value.toHexString

    override def toOctalString(value: Int): String = value.toOctalString

    override def addExact(left: Int, right: Int): Int = Math.addExact(left, right)

    override def subExact(left: Int, right: Int): Int = Math.subtractExact(left, right)

    override def mulExact(left: Int, right: Int): Int = Math.multiplyExact(left, right)

    override def negExact(value: Int): Int = Math.negateExact(value)

    override def floorDiv(left: Int, right: Int): Int = Math.floorDiv(left, right)

    override def floorMod(left: Int, right: Int): Int = Math.floorMod(left, right)

    override def incExact(value: Int): Int = Math.incrementExact(value)

    override def decExact(value: Int): Int = Math.decrementExact(value)
  }
  implicit object NumericInt extends NumericInt

  private val charCase: Schema.Case[NumericChar.type, Numeric[Any]] = Schema.Case[NumericChar.type, Numeric[Any]](
    "NumericChar",
    Schema.singleton[NumericChar.type](NumericChar),
    _.asInstanceOf[NumericChar.type]
  )

  private val shortCase: Schema.Case[NumericShort.type, Numeric[Any]] = Schema.Case[NumericShort.type, Numeric[Any]](
    "NumericShort",
    Schema.singleton[NumericShort.type](NumericShort),
    _.asInstanceOf[NumericShort.type]
  )

  private val longCase: Schema.Case[NumericLong.type, Numeric[Any]] = Schema.Case[NumericLong.type, Numeric[Any]](
    "NumericLong",
    Schema.singleton[NumericLong.type](NumericLong),
    _.asInstanceOf[NumericLong.type]
  )

  private val bigIntCase: Schema.Case[NumericBigInt.type, Numeric[Any]] =
    Schema.Case[NumericBigInt.type, Numeric[Any]](
      "NumericBigInt",
      Schema.singleton[NumericBigInt.type](NumericBigInt),
      _.asInstanceOf[NumericBigInt.type]
    )

  private val floatCase: Schema.Case[NumericFloat.type, Numeric[Any]] = Schema.Case[NumericFloat.type, Numeric[Any]](
    "NumericFloat",
    Schema.singleton[NumericFloat.type](NumericFloat),
    _.asInstanceOf[NumericFloat.type]
  )

  private val doubleCase: Schema.Case[NumericDouble.type, Numeric[Any]] =
    Schema.Case[NumericDouble.type, Numeric[Any]](
      "NumericDouble",
      Schema.singleton[NumericDouble.type](NumericDouble),
      _.asInstanceOf[NumericDouble.type]
    )

  private val bigDecimalCase: Schema.Case[NumericBigDecimal.type, Numeric[Any]] =
    Schema.Case[NumericBigDecimal.type, Numeric[Any]](
      "NumericBigDecimal",
      Schema.singleton[NumericBigDecimal.type](NumericBigDecimal),
      _.asInstanceOf[NumericBigDecimal.type]
    )

  private val intCase: Schema.Case[NumericInt.type, Numeric[Any]] = Schema.Case[NumericInt.type, Numeric[Any]](
    "NumericInt",
    Schema.singleton[NumericInt.type](NumericInt),
    _.asInstanceOf[NumericInt.type]
  )

  implicit val schema: Schema[Numeric[Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.numeric.Numeric"),
      CaseSet
        .Cons(
          shortCase,
          CaseSet.Empty[Numeric[Any]]()
        )
        .:+:(longCase)
        .:+:(charCase)
        .:+:(bigIntCase)
        .:+:(floatCase)
        .:+:(doubleCase)
        .:+:(bigDecimalCase)
        .:+:(intCase)
    )

}

sealed trait NumericImplicits0 {

  trait NumericChar extends Numeric[Char] with Integral[Char] {
    override val schema: Schema[Char] = implicitly[Schema[Char]]

    override def conversions(value: Char): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[Char] = Remote(value.toChar)

    override def parse(s: String): Option[Char] =
      s.headOption

    override def add(left: Char, right: Char): Char = (left + right).toChar

    override def sub(left: Char, right: Char): Char = (left - right).toChar

    override def mul(left: Char, right: Char): Char = (left * right).toChar

    override def div(left: Char, right: Char): Char = (left / right).toChar

    override def mod(left: Char, right: Char): Char = (left % right).toChar

    override def neg(value: Char): Char = (-value).toChar

    override def bitwiseNegate(value: Char): Char = (~value).toChar

    override def abs(value: Char): Char = value.abs

    override def sign(value: Char): Char = if (value == 0) 0 else 1

    override def min(left: Char, right: Char): Char = left.min(right)

    override def max(left: Char, right: Char): Char = left.max(right)

    override def leftShift(left: Char, right: Char): Char = (left << right.toInt).toChar

    override def rightShift(left: Char, right: Char): Char = (left >> right.toInt).toChar

    override def unsignedRightShift(left: Char, right: Char): Char = (left >>> right.toInt).toChar

    override def and(left: Char, right: Char): Char = (left & right).toChar

    override def or(left: Char, right: Char): Char = (left | right).toChar

    override def xor(left: Char, right: Char): Char = (left ^ right).toChar

    override def toInt(value: Char): Int = value.toInt

    override def toShort(value: Char): Short = value.toShort

    override def toLong(value: Char): Long = value.toLong

    override def toFloat(value: Char): Float = value.toFloat

    override def toDouble(value: Char): Double = value.toDouble

    override def toBigDecimal(value: Char): BigDecimal = BigDecimal(value.toInt)

    override def toBinaryString(value: Char): String = value.toInt.toBinaryString

    override def toHexString(value: Char): String = value.toInt.toHexString

    override def toOctalString(value: Char): String = value.toInt.toOctalString

    override def addExact(left: Char, right: Char): Char = Math.addExact(left.toInt, right.toInt).toChar

    override def subExact(left: Char, right: Char): Char = Math.subtractExact(left.toInt, right.toInt).toChar

    override def mulExact(left: Char, right: Char): Char = Math.multiplyExact(left.toInt, right.toInt).toChar

    override def negExact(value: Char): Char = Math.negateExact(value.toInt).toChar

    override def floorDiv(left: Char, right: Char): Char = Math.floorDiv(left.toInt, right.toInt).toChar

    override def floorMod(left: Char, right: Char): Char = Math.floorMod(left.toInt, right.toInt).toChar

    override def incExact(value: Char): Char = Math.incrementExact(value.toInt).toChar

    override def decExact(value: Char): Char = Math.decrementExact(value.toInt).toChar
  }

  implicit case object NumericChar extends NumericChar

  trait NumericShort extends Numeric[Short] with Integral[Short] {
    override val schema: Schema[Short] = implicitly[Schema[Short]]

    override def conversions(value: Short): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[Short] = Remote(value.toShort)

    override def parse(s: String): Option[Short] =
      Try(s.toShort).toOption

    override def add(left: Short, right: Short): Short = (left + right).toShort

    override def sub(left: Short, right: Short): Short = (left - right).toShort

    override def mul(left: Short, right: Short): Short = (left * right).toShort

    override def div(left: Short, right: Short): Short = (left / right).toShort

    override def mod(left: Short, right: Short): Short = (left % right).toShort

    override def neg(value: Short): Short = (-value).toShort

    override def bitwiseNegate(value: Short): Short = (~value).toShort

    override def abs(value: Short): Short = value.abs

    override def sign(value: Short): Short = if (value == 0) 0 else if (value < 0) -1 else 1

    override def min(left: Short, right: Short): Short = left.min(right)

    override def max(left: Short, right: Short): Short = left.max(right)

    override def leftShift(left: Short, right: Short): Short = (left << right.toInt).toShort

    override def rightShift(left: Short, right: Short): Short = (left >> right.toInt).toShort

    override def unsignedRightShift(left: Short, right: Short): Short = (left >>> right.toInt).toShort

    override def and(left: Short, right: Short): Short = (left & right).toShort

    override def or(left: Short, right: Short): Short = (left | right).toShort

    override def xor(left: Short, right: Short): Short = (left ^ right).toShort

    override def toInt(value: Short): Int = value.toInt

    override def toShort(value: Short): Short = value

    override def toLong(value: Short): Long = value.toLong

    override def toFloat(value: Short): Float = value.toFloat

    override def toDouble(value: Short): Double = value.toDouble

    override def toBigDecimal(value: Short): BigDecimal = BigDecimal(value.toLong)

    override def toBinaryString(value: Short): String = value.toInt.toBinaryString

    override def toHexString(value: Short): String = value.toInt.toHexString

    override def toOctalString(value: Short): String = value.toInt.toOctalString

    override def addExact(left: Short, right: Short): Short = Math.addExact(left.toInt, right.toInt).toShort

    override def subExact(left: Short, right: Short): Short = Math.subtractExact(left.toInt, right.toInt).toShort

    override def mulExact(left: Short, right: Short): Short = Math.multiplyExact(left.toInt, right.toInt).toShort

    override def negExact(value: Short): Short = Math.negateExact(value.toInt).toShort

    override def floorDiv(left: Short, right: Short): Short = Math.floorDiv(left.toInt, right.toInt).toShort

    override def floorMod(left: Short, right: Short): Short = Math.floorMod(left.toInt, right.toInt).toShort

    override def incExact(value: Short): Short = Math.incrementExact(value.toInt).toShort

    override def decExact(value: Short): Short = Math.decrementExact(value.toInt).toShort
  }
  implicit case object NumericShort extends NumericShort

  trait NumericLong extends Numeric[Long] with Integral[Long] {
    override val schema: Schema[Long] = implicitly[Schema[Long]]

    override def conversions(value: Long): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[Long] = Remote(value)

    override def parse(s: String): Option[Long] =
      Try(s.toLong).toOption

    override def add(left: Long, right: Long): Long = left + right

    override def sub(left: Long, right: Long): Long = left - right

    override def mul(left: Long, right: Long): Long = left * right

    override def div(left: Long, right: Long): Long = left / right

    override def mod(left: Long, right: Long): Long = left % right

    override def neg(value: Long): Long = -value

    override def bitwiseNegate(left: Long): Long = ~left

    override def abs(value: Long): Long = value.abs

    override def sign(value: Long): Long = if (value == 0) 0 else if (value < 0) -1 else 1

    override def min(left: Long, right: Long): Long = left.min(right)

    override def max(left: Long, right: Long): Long = left.max(right)

    override def isValidLong(value: Long): Boolean = value.isValidLong

    override def leftShift(left: Long, right: Long): Long = left << right

    override def rightShift(left: Long, right: Long): Long = left >> right

    override def unsignedRightShift(left: Long, right: Long): Long = left >>> right

    override def and(left: Long, right: Long): Long = left & right

    override def or(left: Long, right: Long): Long = left | right

    override def xor(left: Long, right: Long): Long = left ^ right

    override def toInt(value: Long): Int = value.toInt

    override def toShort(value: Long): Short = value.toShort

    override def toLong(value: Long): Long = value

    override def toFloat(value: Long): Float = value.toFloat

    override def toDouble(value: Long): Double = value.toDouble

    override def toBigDecimal(value: Long): BigDecimal = BigDecimal(value)

    override def toBinaryString(value: Long): String = value.toBinaryString

    override def toHexString(value: Long): String = value.toHexString

    override def toOctalString(value: Long): String = value.toOctalString

    override def addExact(left: Long, right: Long): Long = Math.addExact(left, right)

    override def subExact(left: Long, right: Long): Long = Math.subtractExact(left, right)

    override def mulExact(left: Long, right: Long): Long = Math.multiplyExact(left, right)

    override def negExact(value: Long): Long = Math.negateExact(value)

    override def floorDiv(left: Long, right: Long): Long = Math.floorDiv(left, right)

    override def floorMod(left: Long, right: Long): Long = Math.floorMod(left, right)

    override def incExact(value: Long): Long = Math.incrementExact(value)

    override def decExact(value: Long): Long = Math.decrementExact(value)
  }
  implicit case object NumericLong extends NumericLong

  trait NumericBigInt extends Numeric[BigInt] with Integral[BigInt] {
    override val schema: Schema[BigInt] = implicitly[Schema[BigInt]]

    override def conversions(value: BigInt): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[BigInt] = Remote(BigInt(value))

    override def parse(s: String): Option[BigInt] =
      Try(BigInt(s)).toOption

    override def add(left: BigInt, right: BigInt): BigInt = left + right

    override def mul(left: BigInt, right: BigInt): BigInt = left * right

    override def div(left: BigInt, right: BigInt): BigInt = left / right

    override def mod(left: BigInt, right: BigInt): BigInt = left % right

    override def neg(value: BigInt): BigInt = -value

    override def bitwiseNegate(left: BigInt): BigInt = ~left

    override def abs(value: BigInt): BigInt = value.abs

    override def sign(value: BigInt): BigInt = if (value == 0) 0 else if (value < 0) -1 else 1

    override def min(left: BigInt, right: BigInt): BigInt = left.min(right)

    override def max(left: BigInt, right: BigInt): BigInt = left.max(right)

    override def leftShift(left: BigInt, right: BigInt): BigInt = left << right.toInt

    override def rightShift(left: BigInt, right: BigInt): BigInt = left >> right.toInt

    override def unsignedRightShift(left: BigInt, right: BigInt): BigInt = left >> right.toInt

    override def and(left: BigInt, right: BigInt): BigInt = left & right

    override def or(left: BigInt, right: BigInt): BigInt = left | right

    override def xor(left: BigInt, right: BigInt): BigInt = left ^ right

    override def toInt(value: BigInt): Int = value.toInt

    override def toShort(value: BigInt): Short = value.toShort

    override def toLong(value: BigInt): Long = value.toLong

    override def toFloat(value: BigInt): Float = value.toFloat

    override def toDouble(value: BigInt): Double = value.toDouble

    override def toBigDecimal(value: BigInt): BigDecimal = BigDecimal(value)

    override def toBinaryString(value: BigInt): String = value.toString(2)

    override def toHexString(value: BigInt): String = value.toString(16)

    override def toOctalString(value: BigInt): String = value.toString(8)

    override def addExact(left: BigInt, right: BigInt): BigInt = left + right

    override def sub(left: BigInt, right: BigInt): BigInt = left - right

    override def subExact(left: BigInt, right: BigInt): BigInt = left - right

    override def mulExact(left: BigInt, right: BigInt): BigInt = left * right

    override def negExact(value: BigInt): BigInt = -value

    override def floorDiv(left: BigInt, right: BigInt): BigInt = {
      val r = left / right
      // if the signs are different and modulo not zero, round down
      if ((left ^ right) < 0 && (r * right != left)) {
        r - 1
      } else r
    }

    override def floorMod(left: BigInt, right: BigInt): BigInt = {
      val mod = left % right
      // if the signs are different and modulo not zero, adjust result
      if ((mod ^ right) < 0 && mod != 0) {
        mod + right
      } else mod
    }

    override def incExact(value: BigInt): BigInt = value + 1

    override def decExact(value: BigInt): BigInt = value - 1
  }
  implicit case object NumericBigInt extends NumericBigInt

  trait NumericFloat extends Numeric[Float] {
    override val schema: Schema[Float] = implicitly[Schema[Float]]

    override def conversions(value: Float): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[Float] = Remote(value.toFloat)

    override def parse(s: String): Option[Float] =
      Try(s.toFloat).toOption

    override def add(left: Float, right: Float): Float = left + right

    override def sub(left: Float, right: Float): Float = left - right

    override def mul(left: Float, right: Float): Float = left * right

    override def div(left: Float, right: Float): Float = left / right

    override def mod(left: Float, right: Float): Float = left % right

    override def neg(value: Float): Float = -value

    override def abs(value: Float): Float = value.abs

    override def sign(value: Float): Float = if (value == 0) 0 else if (value < 0) -1 else 1

    override def min(left: Float, right: Float): Float = left.min(right)

    override def max(left: Float, right: Float): Float = left.max(right)

    override def toInt(value: Float): Int = value.toInt

    override def toShort(value: Float): Short = value.toShort

    override def toLong(value: Float): Long = value.toLong

    override def toFloat(value: Float): Float = value

    override def toDouble(value: Float): Double = value.toDouble

    override def toBigDecimal(value: Float): BigDecimal = BigDecimal(value.toDouble)
  }
  implicit case object NumericFloat extends NumericFloat

  trait NumericDouble extends Numeric[Double] {
    override val schema: Schema[Double] = implicitly[Schema[Double]]

    override def conversions(value: Double): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[Double] = Remote(value.toDouble)

    override def parse(s: String): Option[Double] =
      Try(s.toDouble).toOption

    override def add(left: Double, right: Double): Double = left + right

    override def sub(left: Double, right: Double): Double = left - right

    override def mul(left: Double, right: Double): Double = left * right

    override def div(left: Double, right: Double): Double = left / right

    override def mod(left: Double, right: Double): Double = left % right

    override def neg(value: Double): Double = -value

    override def abs(value: Double): Double = value.abs

    override def sign(value: Double): Double = if (value == 0) 0 else if (value < 0) -1 else 1

    override def min(left: Double, right: Double): Double = left.min(right)

    override def max(left: Double, right: Double): Double = left.max(right)

    override def toInt(value: Double): Int = value.toInt

    override def toShort(value: Double): Short = value.toShort

    override def toLong(value: Double): Long = value.toLong

    override def toFloat(value: Double): Float = value.toFloat

    override def toDouble(value: Double): Double = value

    override def toBigDecimal(value: Double): BigDecimal = BigDecimal(value)
  }
  implicit case object NumericDouble extends NumericDouble

  trait NumericBigDecimal extends Numeric[BigDecimal] {
    override val schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]

    override def conversions(value: BigDecimal): ScalaNumericAnyConversions = value

    override def fromLong(value: Long): Remote[BigDecimal] = Remote(BigDecimal(value))

    override def parse(s: String): Option[BigDecimal] =
      Try(BigDecimal(s)).toOption

    override def add(left: BigDecimal, right: BigDecimal): BigDecimal = left + right

    override def sub(left: BigDecimal, right: BigDecimal): BigDecimal = left - right

    override def mul(left: BigDecimal, right: BigDecimal): BigDecimal = left * right

    override def div(left: BigDecimal, right: BigDecimal): BigDecimal = left / right

    override def mod(left: BigDecimal, right: BigDecimal): BigDecimal = left % right

    override def neg(value: BigDecimal): BigDecimal = -value

    override def abs(value: BigDecimal): BigDecimal = value.abs

    override def sign(value: BigDecimal): BigDecimal = if (value == 0) 0 else if (value < 0) -1 else 1

    override def min(left: BigDecimal, right: BigDecimal): BigDecimal = left.min(right)

    override def max(left: BigDecimal, right: BigDecimal): BigDecimal = left.max(right)

    override def toInt(value: BigDecimal): Int = value.toInt

    override def toShort(value: BigDecimal): Short = value.toShort

    override def toLong(value: BigDecimal): Long = value.toLong

    override def toFloat(value: BigDecimal): Float = value.toFloat

    override def toDouble(value: BigDecimal): Double = value.toDouble

    override def toBigDecimal(value: BigDecimal): BigDecimal = value
  }
  implicit case object NumericBigDecimal extends NumericBigDecimal
}

sealed trait Integral[A] {
  val schema: Schema[A]

  def bitwiseNegate(left: A): A

  def leftShift(left: A, right: A): A
  def rightShift(left: A, right: A): A

  def unsignedRightShift(left: A, right: A): A
  def and(left: A, right: A): A
  def or(left: A, right: A): A
  def xor(left: A, right: A): A

  def floorDiv(left: A, right: A): A
  def floorMod(left: A, right: A): A
  def incExact(value: A): A
  def decExact(value: A): A
  def addExact(left: A, right: A): A
  def subExact(left: A, right: A): A
  def mulExact(left: A, right: A): A
  def negExact(value: A): A

  def toBinaryString(value: A): String
  def toHexString(value: A): String
  def toOctalString(value: A): String

  def unary(op: UnaryIntegralOperator, value: A): A =
    op match {
      case UnaryIntegralOperator.BitwiseNeg => bitwiseNegate(value)
      case UnaryIntegralOperator.NegExact   => negExact(value)
      case UnaryIntegralOperator.IncExact   => incExact(value)
      case UnaryIntegralOperator.DecExact   => decExact(value)
    }

  def binary(op: BinaryIntegralOperator, left: A, right: A): A =
    op match {
      case BinaryIntegralOperator.LeftShift          => leftShift(left, right)
      case BinaryIntegralOperator.RightShift         => rightShift(left, right)
      case BinaryIntegralOperator.UnsignedRightShift => unsignedRightShift(left, right)
      case BinaryIntegralOperator.And                => and(left, right)
      case BinaryIntegralOperator.Or                 => or(left, right)
      case BinaryIntegralOperator.Xor                => xor(left, right)
      case BinaryIntegralOperator.FloorDiv           => floorDiv(left, right)
      case BinaryIntegralOperator.FloorMod           => floorMod(left, right)
      case BinaryIntegralOperator.AddExact           => addExact(left, right)
      case BinaryIntegralOperator.SubExact           => subExact(left, right)
      case BinaryIntegralOperator.MulExact           => mulExact(left, right)
    }
}

object Integral {

  private val shortCase: Schema.Case[Numeric.NumericShort.type, Integral[Any]] =
    Schema.Case[Numeric.NumericShort.type, Integral[Any]](
      "NumericShort",
      Schema.singleton[Numeric.NumericShort.type](Numeric.NumericShort),
      _.asInstanceOf[Numeric.NumericShort.type]
    )

  private val longCase: Schema.Case[Numeric.NumericLong.type, Integral[Any]] =
    Schema.Case[Numeric.NumericLong.type, Integral[Any]](
      "NumericLong",
      Schema.singleton[Numeric.NumericLong.type](Numeric.NumericLong),
      _.asInstanceOf[Numeric.NumericLong.type]
    )

  private val bigIntCase: Schema.Case[Numeric.NumericBigInt.type, Integral[Any]] =
    Schema.Case[Numeric.NumericBigInt.type, Integral[Any]](
      "NumericBigInt",
      Schema.singleton[Numeric.NumericBigInt.type](Numeric.NumericBigInt),
      _.asInstanceOf[Numeric.NumericBigInt.type]
    )

  private val intCase: Schema.Case[NumericInt.type, Integral[Any]] =
    Schema.Case[NumericInt.type, Integral[Any]](
      "NumericInt",
      Schema.singleton[NumericInt.type](NumericInt),
      _.asInstanceOf[NumericInt.type]
    )

  implicit val schema: Schema[Integral[Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.numeric.Integral"),
      CaseSet
        .Cons(
          shortCase,
          CaseSet.Empty[Integral[Any]]()
        )
        .:+:(longCase)
        .:+:(bigIntCase)
        .:+:(intCase)
    )
}

sealed trait Fractional[A] extends Numeric[A] {
  def fromDouble(const: Double): A

  def sin(value: A): A

  def cos(value: A): A

  def asin(value: A): A

  def acos(value: A): A

  def tan(value: A): A

  def atan(value: A): A

  def pow(left: A, right: A): A

  def log(value: A): A

  def floor(value: A): A

  def ceil(value: A): A

  def round(value: A): A

  def toRadians(value: A): A

  def toDegrees(value: A): A

  def rint(value: A): A

  def nextUp(value: A): A

  def nextDown(value: A): A

  def scalb(left: A, right: A): A

  def sqrt(value: A): A

  def cbrt(value: A): A

  def exp(value: A): A

  def expm1(value: A): A

  def log1p(value: A): A

  def log10(value: A): A

  def sinh(value: A): A

  def cosh(value: A): A

  def tanh(value: A): A

  def ulp(value: A): A

  def atan2(left: A, right: A): A

  def hypot(left: A, right: A): A

  def copySign(left: A, right: A): A

  def nextAfter(left: A, right: A): A

  def IEEEremainder(left: A, right: A): A

  def isNaN(value: A): Boolean

  def isInfinity(value: A): Boolean

  def isFinite(value: A): Boolean

  def isPosInfinity(value: A): Boolean

  def isNegInfinity(value: A): Boolean

  def getExponent(value: A): Int

  def unary(operator: UnaryFractionalOperator, value: A): A =
    operator match {
      case UnaryFractionalOperator.Sin       => sin(value)
      case UnaryFractionalOperator.Cos       => cos(value)
      case UnaryFractionalOperator.ArcSin    => asin(value)
      case UnaryFractionalOperator.ArcCos    => acos(value)
      case UnaryFractionalOperator.Tan       => tan(value)
      case UnaryFractionalOperator.ArcTan    => atan(value)
      case UnaryFractionalOperator.Floor     => floor(value)
      case UnaryFractionalOperator.Ceil      => ceil(value)
      case UnaryFractionalOperator.Round     => round(value)
      case UnaryFractionalOperator.ToRadians => toRadians(value)
      case UnaryFractionalOperator.ToDegrees => toDegrees(value)
      case UnaryFractionalOperator.Rint      => rint(value)
      case UnaryFractionalOperator.NextUp    => nextUp(value)
      case UnaryFractionalOperator.NextDown  => nextDown(value)
      case UnaryFractionalOperator.Sqrt      => sqrt(value)
      case UnaryFractionalOperator.Cbrt      => cbrt(value)
      case UnaryFractionalOperator.Exp       => exp(value)
      case UnaryFractionalOperator.Expm1     => expm1(value)
      case UnaryFractionalOperator.Log       => log(value)
      case UnaryFractionalOperator.Log1p     => log1p(value)
      case UnaryFractionalOperator.Log10     => log10(value)
      case UnaryFractionalOperator.Sinh      => sinh(value)
      case UnaryFractionalOperator.Cosh      => cosh(value)
      case UnaryFractionalOperator.Tanh      => tanh(value)
      case UnaryFractionalOperator.Ulp       => ulp(value)
    }

  def binary(operator: BinaryFractionalOperator, left: A, right: A): A =
    operator match {
      case BinaryFractionalOperator.Pow           => pow(left, right)
      case BinaryFractionalOperator.ArcTan2       => atan2(left, right)
      case BinaryFractionalOperator.Hypot         => hypot(left, right)
      case BinaryFractionalOperator.Scalb         => scalb(left, right)
      case BinaryFractionalOperator.CopySign      => copySign(left, right)
      case BinaryFractionalOperator.NextAfter     => nextAfter(left, right)
      case BinaryFractionalOperator.IEEEremainder => IEEEremainder(left, right)
    }

  def predicate(operator: FractionalPredicateOperator, value: A): Boolean =
    operator match {
      case FractionalPredicateOperator.IsNaN         => isNaN(value)
      case FractionalPredicateOperator.IsInfinity    => isInfinity(value)
      case FractionalPredicateOperator.IsFinite      => isFinite(value)
      case FractionalPredicateOperator.IsPosInfinity => isPosInfinity(value)
      case FractionalPredicateOperator.IsNegInifinty => isNegInfinity(value)
    }
}

object Fractional {

  implicit case object FractionalFloat extends Numeric.NumericFloat with Fractional[Float] {
    override def fromDouble(const: Double): Float = const.toFloat

    override def sin(value: Float): Float = Math.sin(value.toDouble).toFloat
    override def cos(value: Float): Float = Math.cos(value.toDouble).toFloat

    override def asin(value: Float): Float = Math.asin(value.toDouble).toFloat
    override def acos(value: Float): Float = Math.acos(value.toDouble).toFloat

    override def tan(value: Float): Float  = Math.tan(value.toDouble).toFloat
    override def atan(value: Float): Float = Math.atan(value.toDouble).toFloat

    override def floor(value: Float): Float = Math.floor(value.toDouble).toFloat

    override def ceil(value: Float): Float = Math.ceil(value.toDouble).toFloat

    override def round(value: Float): Float = Math.round(value).toFloat

    override def pow(left: Float, right: Float): Float = Math.pow(left.toDouble, right.toDouble).toFloat

    override def log(value: Float): Float = Math.log(value.toDouble).toFloat

    override def toRadians(value: Float): Float = value.toRadians

    override def toDegrees(value: Float): Float = value.toDegrees

    override def isNaN(value: Float): Boolean = value.isNaN

    override def isInfinity(value: Float): Boolean = value.isInfinity

    override def isFinite(value: Float): Boolean = java.lang.Float.isFinite(value)

    override def isPosInfinity(value: Float): Boolean = value.isPosInfinity

    override def isNegInfinity(value: Float): Boolean = value.isNegInfinity

    override def rint(value: Float): Float = math.rint(value.toDouble).toFloat

    override def nextUp(value: Float): Float = Math.nextUp(value)

    override def nextDown(value: Float): Float = Math.nextDown(value)

    override def scalb(left: Float, right: Float): Float = Math.scalb(left, right.toInt)

    override def sqrt(value: Float): Float = math.sqrt(value.toDouble).toFloat

    override def cbrt(value: Float): Float = math.cbrt(value.toDouble).toFloat

    override def exp(value: Float): Float = math.exp(value.toDouble).toFloat

    override def expm1(value: Float): Float = math.expm1(value.toDouble).toFloat

    override def log1p(value: Float): Float = math.log1p(value.toDouble).toFloat

    override def log10(value: Float): Float = math.log10(value.toDouble).toFloat

    override def sinh(value: Float): Float = math.sinh(value.toDouble).toFloat

    override def cosh(value: Float): Float = math.cosh(value.toDouble).toFloat

    override def tanh(value: Float): Float = math.tanh(value.toDouble).toFloat

    override def ulp(value: Float): Float = math.ulp(value)

    override def atan2(left: Float, right: Float): Float = math.atan2(left.toDouble, right.toDouble).toFloat

    override def hypot(left: Float, right: Float): Float = math.hypot(left.toDouble, right.toDouble).toFloat

    override def copySign(left: Float, right: Float): Float = Math.copySign(left, right)

    override def nextAfter(left: Float, right: Float): Float = Math.nextAfter(left, right.toDouble)

    override def IEEEremainder(left: Float, right: Float): Float =
      math.IEEEremainder(left.toDouble, right.toDouble).toFloat

    override def getExponent(value: Float): Int = Math.getExponent(value)

    override def sub(left: Float, right: Float): Float = left - right
  }

  implicit case object FractionalDouble extends Numeric.NumericDouble with Fractional[Double] {

    override def fromDouble(const: Double): Double = const

    override def sin(value: Double): Double = Math.sin(value)
    override def cos(value: Double): Double = Math.cos(value)

    override def asin(value: Double): Double = Math.asin(value)
    override def acos(value: Double): Double = Math.acos(value)

    override def tan(value: Double): Double  = Math.tan(value)
    override def atan(value: Double): Double = Math.atan(value)

    override def floor(value: Double): Double = Math.floor(value)

    override def ceil(value: Double): Double = Math.ceil(value)

    override def round(value: Double): Double = value.round.toDouble

    override def pow(left: Double, right: Double): Double = Math.pow(left, right)

    override def log(value: Double): Double = Math.log(value)

    override def toRadians(value: Double): Double = value.toRadians

    override def toDegrees(value: Double): Double = value.toDegrees

    override def isNaN(value: Double): Boolean = value.isNaN

    override def isInfinity(value: Double): Boolean = value.isInfinity

    override def isFinite(value: Double): Boolean = java.lang.Double.isFinite(value)

    override def isPosInfinity(value: Double): Boolean = value.isPosInfinity

    override def isNegInfinity(value: Double): Boolean = value.isNegInfinity

    override def rint(value: Double): Double = math.rint(value)

    override def nextUp(value: Double): Double = Math.nextUp(value)

    override def nextDown(value: Double): Double = Math.nextDown(value)

    override def scalb(left: Double, right: Double): Double = Math.scalb(left, right.toInt)

    override def sqrt(value: Double): Double = math.sqrt(value)

    override def cbrt(value: Double): Double = math.cbrt(value)

    override def exp(value: Double): Double = math.exp(value)

    override def expm1(value: Double): Double = math.expm1(value)

    override def log1p(value: Double): Double = math.log1p(value)

    override def log10(value: Double): Double = math.log10(value)

    override def sinh(value: Double): Double = math.sinh(value)

    override def cosh(value: Double): Double = math.cosh(value)

    override def tanh(value: Double): Double = math.tanh(value)

    override def ulp(value: Double): Double = math.ulp(value)

    override def atan2(left: Double, right: Double): Double = math.atan2(left, right)

    override def hypot(left: Double, right: Double): Double = math.hypot(left, right)

    override def copySign(left: Double, right: Double): Double = Math.copySign(left, right)

    override def nextAfter(left: Double, right: Double): Double = Math.nextAfter(left, right)

    override def IEEEremainder(left: Double, right: Double): Double = math.IEEEremainder(left, right)

    override def getExponent(value: Double): Int = Math.getExponent(value)
  }

  implicit case object FractionalBigDecimal extends Numeric.NumericBigDecimal with Fractional[BigDecimal] {
    override def fromDouble(const: Double): BigDecimal = BigDecimal(const)

    override def sin(value: BigDecimal): BigDecimal = Math.sin(value.doubleValue)
    override def cos(value: BigDecimal): BigDecimal = Math.cos(value.doubleValue)

    override def asin(value: BigDecimal): BigDecimal = Math.asin(value.doubleValue)
    override def acos(value: BigDecimal): BigDecimal = Math.acos(value.doubleValue)

    override def tan(value: BigDecimal): BigDecimal  = Math.tan(value.doubleValue)
    override def atan(value: BigDecimal): BigDecimal = Math.atan(value.doubleValue)

    override def floor(value: BigDecimal): BigDecimal = Math.floor(value.doubleValue)

    override def ceil(value: BigDecimal): BigDecimal = Math.ceil(value.doubleValue)

    override def round(value: BigDecimal): BigDecimal = value.rounded

    override def pow(left: BigDecimal, right: BigDecimal): BigDecimal =
      BigDecimal(
        Math.pow(left.doubleValue, right.doubleValue)
      )

    override def log(value: BigDecimal): BigDecimal =
      Math.log(value.doubleValue)

    override def toRadians(value: BigDecimal): BigDecimal = value * Math.toRadians(1.0)

    override def toDegrees(value: BigDecimal): BigDecimal = value * Math.toDegrees(1.0)

    override def isNaN(value: BigDecimal): Boolean = false

    override def isInfinity(value: BigDecimal): Boolean = false

    override def isFinite(value: BigDecimal): Boolean = true

    override def isPosInfinity(value: BigDecimal): Boolean = false

    override def isNegInfinity(value: BigDecimal): Boolean = false

    override def rint(value: BigDecimal): BigDecimal = BigDecimal(math.rint(value.toDouble))

    override def nextUp(value: BigDecimal): BigDecimal = BigDecimal(Math.nextUp(value.toDouble))

    override def nextDown(value: BigDecimal): BigDecimal = BigDecimal(Math.nextDown(value.toDouble))

    override def scalb(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      Math.scalb(left.toDouble, right.toInt)
    )

    override def sqrt(value: BigDecimal): BigDecimal = BigDecimal(math.sqrt(value.toDouble))

    override def cbrt(value: BigDecimal): BigDecimal = BigDecimal(math.cbrt(value.toDouble))

    override def exp(value: BigDecimal): BigDecimal = BigDecimal(math.exp(value.toDouble))

    override def expm1(value: BigDecimal): BigDecimal = BigDecimal(math.expm1(value.toDouble))

    override def log1p(value: BigDecimal): BigDecimal = BigDecimal(math.log1p(value.toDouble))

    override def log10(value: BigDecimal): BigDecimal = BigDecimal(math.log10(value.toDouble))

    override def sinh(value: BigDecimal): BigDecimal = BigDecimal(math.sinh(value.toDouble))

    override def cosh(value: BigDecimal): BigDecimal = BigDecimal(math.cosh(value.toDouble))

    override def tanh(value: BigDecimal): BigDecimal = BigDecimal(math.tanh(value.toDouble))

    override def ulp(value: BigDecimal): BigDecimal = BigDecimal(math.ulp(value.toDouble))

    override def atan2(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      math.atan2(left.toDouble, right.toDouble)
    )

    override def hypot(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      math.hypot(left.toDouble, right.toDouble)
    )

    override def copySign(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      Math.copySign(left.toDouble, right.toDouble)
    )

    override def nextAfter(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      Math.nextAfter(left.toDouble, right.toDouble)
    )

    override def IEEEremainder(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      math.IEEEremainder(left.toDouble, right.toDouble)
    )

    override def getExponent(value: BigDecimal): Int = Math.getExponent(value.toDouble)
  }

  private val floatCase: Schema.Case[FractionalFloat.type, Fractional[Any]] =
    Schema.Case[FractionalFloat.type, Fractional[Any]](
      "FractionalFloat",
      Schema.singleton[FractionalFloat.type](FractionalFloat),
      _.asInstanceOf[FractionalFloat.type]
    )

  private val doubleCase: Schema.Case[FractionalDouble.type, Fractional[Any]] =
    Schema.Case[FractionalDouble.type, Fractional[Any]](
      "FractionalDouble",
      Schema.singleton[FractionalDouble.type](FractionalDouble),
      _.asInstanceOf[FractionalDouble.type]
    )

  private val bigDecimalCase: Schema.Case[FractionalBigDecimal.type, Fractional[Any]] =
    Schema.Case[FractionalBigDecimal.type, Fractional[Any]](
      "FractionalBigDecimal",
      Schema.singleton[FractionalBigDecimal.type](FractionalBigDecimal),
      _.asInstanceOf[FractionalBigDecimal.type]
    )

  implicit val schema: Schema[Fractional[Any]] =
    Schema.EnumN(
      TypeId.parse("zio.flow.remote.numeric.Fractional"),
      CaseSet
        .Cons(
          floatCase,
          CaseSet.Empty[Fractional[Any]]()
        )
        .:+:(doubleCase)
        .:+:(bigDecimalCase)
    )
}
