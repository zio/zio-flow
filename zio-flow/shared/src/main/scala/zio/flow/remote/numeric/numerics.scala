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
import zio.schema.{CaseSet, Schema}

import scala.annotation.nowarn
import scala.math.ScalaNumericAnyConversions

sealed trait Numeric[A] {
  val schema: Schema[A]

  def conversions(value: A): ScalaNumericAnyConversions

  def fromLong(l: Long): Remote[A]

  def add(left: A, right: A): A

  def multiply(left: A, right: A): A

  def divide(left: A, right: A): A

  def mod(left: A, right: A): A

  def negate(left: A): A

  def abs(left: A): A

  def sign(left: A): A

  def min(left: A, right: A): A

  def max(left: A, right: A): A

  @nowarn def isValidLong(value: A): Boolean = false

  def toInt(value: A): Int

  def toShort(value: A): Short

  def toLong(value: A): Long

  def toFloat(value: A): Float

  def toDouble(value: A): Double

  def unary(op: UnaryNumericOperator, value: A): A =
    op match {
      case UnaryNumericOperator.Neg  => negate(value)
      case UnaryNumericOperator.Abs  => abs(value)
      case UnaryNumericOperator.Sign => sign(value)
    }

  def binary(op: BinaryNumericOperator, left: A, right: A): A =
    op match {
      case BinaryNumericOperator.Add => add(left, right)
      case BinaryNumericOperator.Mul => multiply(left, right)
      case BinaryNumericOperator.Div => divide(left, right)
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
  trait NumericInt extends Numeric[Int] with BitwiseNumeric[Int] {
    override val schema: Schema[Int] = implicitly[Schema[Int]]

    override def conversions(value: Int): ScalaNumericAnyConversions = value

    override def fromLong(l: Long): Remote[Int] = Remote(l.toInt)

    override def add(left: Int, right: Int): Int = left + right

    override def multiply(left: Int, right: Int): Int = left * right

    override def divide(left: Int, right: Int): Int = left / right

    override def negate(left: Int): Int = -left

    override def bitwiseNegate(left: Int): Int = ~left

    override def mod(left: Int, right: Int): Int = left % right

    override def abs(left: Int): Int = left.abs

    override def sign(left: Int): Int = left.sign

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
  }
  implicit object NumericInt extends NumericInt

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
      CaseSet
        .Cons(
          shortCase,
          CaseSet.Empty[Numeric[Any]]()
        )
        .:+:(longCase)
        .:+:(bigIntCase)
        .:+:(floatCase)
        .:+:(doubleCase)
        .:+:(bigDecimalCase)
        .:+:(intCase)
    )

}

sealed trait NumericImplicits0 {

  trait NumericShort extends Numeric[Short] with BitwiseNumeric[Short] {
    override val schema: Schema[Short] = implicitly[Schema[Short]]

    override def conversions(value: Short): ScalaNumericAnyConversions = value

    override def fromLong(l: Long): Remote[Short] = Remote(l.toShort)

    override def add(left: Short, right: Short): Short = (left + right).toShort

    override def multiply(left: Short, right: Short): Short = (left * right).toShort

    override def divide(left: Short, right: Short): Short = (left / right).toShort

    override def mod(left: Short, right: Short): Short = (left % right).toShort

    override def negate(left: Short): Short = (-left).toShort

    override def bitwiseNegate(left: Short): Short = (~left).toShort

    override def abs(left: Short): Short = left.abs

    override def sign(left: Short): Short = left.sign

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
  }
  implicit case object NumericShort extends NumericShort

  trait NumericLong extends Numeric[Long] with BitwiseNumeric[Long] {
    override val schema: Schema[Long] = implicitly[Schema[Long]]

    override def conversions(value: Long): ScalaNumericAnyConversions = value

    override def fromLong(l: Long): Remote[Long] = Remote(l)

    override def add(left: Long, right: Long): Long = left + right

    override def multiply(left: Long, right: Long): Long = left * right

    override def divide(left: Long, right: Long): Long = left / right

    override def mod(left: Long, right: Long): Long = left % right

    override def negate(left: Long): Long = -left

    override def bitwiseNegate(left: Long): Long = ~left

    override def abs(left: Long): Long = left.abs

    override def sign(left: Long): Long = left.sign

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
  }
  implicit case object NumericLong extends NumericLong

  trait NumericBigInt extends Numeric[BigInt] with BitwiseNumeric[BigInt] {
    override val schema: Schema[BigInt] = implicitly[Schema[BigInt]]

    override def conversions(value: BigInt): ScalaNumericAnyConversions = value

    override def fromLong(l: Long): Remote[BigInt] = Remote(BigInt(l))

    override def add(left: BigInt, right: BigInt): BigInt = left + right

    override def multiply(left: BigInt, right: BigInt): BigInt = left * right

    override def divide(left: BigInt, right: BigInt): BigInt = left / right

    override def mod(left: BigInt, right: BigInt): BigInt = left % right

    override def negate(left: BigInt): BigInt = -left

    override def bitwiseNegate(left: BigInt): BigInt = ~left

    override def abs(left: BigInt): BigInt = left.abs

    override def sign(left: BigInt): BigInt = left.sign

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
  }
  implicit case object NumericBigInt extends NumericBigInt

  trait NumericFloat extends Numeric[Float] {
    override val schema: Schema[Float] = implicitly[Schema[Float]]

    override def conversions(value: Float): ScalaNumericAnyConversions = value

    override def fromLong(l: Long): Remote[Float] = Remote(l.toFloat)

    override def add(left: Float, right: Float): Float = left + right

    override def multiply(left: Float, right: Float): Float = left * right

    override def divide(left: Float, right: Float): Float = left / right

    override def mod(left: Float, right: Float): Float = left % right

    override def negate(left: Float): Float = -left

    override def abs(left: Float): Float = left.abs

    override def sign(left: Float): Float = left.sign

    override def min(left: Float, right: Float): Float = left.min(right)

    override def max(left: Float, right: Float): Float = left.max(right)

    override def toInt(value: Float): Int = value.toInt

    override def toShort(value: Float): Short = value.toShort

    override def toLong(value: Float): Long = value.toLong

    override def toFloat(value: Float): Float = value

    override def toDouble(value: Float): Double = value.toDouble
  }
  implicit case object NumericFloat extends NumericFloat

  trait NumericDouble extends Numeric[Double] {
    override val schema: Schema[Double] = implicitly[Schema[Double]]

    override def conversions(value: Double): ScalaNumericAnyConversions = value

    override def fromLong(l: Long): Remote[Double] = Remote(l.toDouble)

    override def add(left: Double, right: Double): Double = left + right

    override def multiply(left: Double, right: Double): Double = left * right

    override def divide(left: Double, right: Double): Double = left / right

    override def mod(left: Double, right: Double): Double = left % right

    override def negate(left: Double): Double = -left

    override def abs(left: Double): Double = left.abs

    override def sign(left: Double): Double = left.sign

    override def min(left: Double, right: Double): Double = left.min(right)

    override def max(left: Double, right: Double): Double = left.max(right)

    override def toInt(value: Double): Int = value.toInt

    override def toShort(value: Double): Short = value.toShort

    override def toLong(value: Double): Long = value.toLong

    override def toFloat(value: Double): Float = value.toFloat

    override def toDouble(value: Double): Double = value
  }
  implicit case object NumericDouble extends NumericDouble

  trait NumericBigDecimal extends Numeric[BigDecimal] {
    override val schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]

    override def conversions(value: BigDecimal): ScalaNumericAnyConversions = value

    override def fromLong(l: Long): Remote[BigDecimal] = Remote(BigDecimal(l))

    override def add(left: BigDecimal, right: BigDecimal): BigDecimal = left + right

    override def multiply(left: BigDecimal, right: BigDecimal): BigDecimal = left * right

    override def divide(left: BigDecimal, right: BigDecimal): BigDecimal = left / right

    override def mod(left: BigDecimal, right: BigDecimal): BigDecimal = left % right

    override def negate(left: BigDecimal): BigDecimal = -left

    override def abs(left: BigDecimal): BigDecimal = left.abs

    override def sign(left: BigDecimal): BigDecimal = left.sign

    override def min(left: BigDecimal, right: BigDecimal): BigDecimal = left.min(right)

    override def max(left: BigDecimal, right: BigDecimal): BigDecimal = left.max(right)

    override def toInt(value: BigDecimal): Int = value.toInt

    override def toShort(value: BigDecimal): Short = value.toShort

    override def toLong(value: BigDecimal): Long = value.toLong

    override def toFloat(value: BigDecimal): Float = value.toFloat

    override def toDouble(value: BigDecimal): Double = value.toDouble
  }
  implicit case object NumericBigDecimal extends NumericBigDecimal
}

sealed trait BitwiseNumeric[A] {
  val schema: Schema[A]

  def bitwiseNegate(left: A): A

  def leftShift(left: A, right: A): A
  def rightShift(left: A, right: A): A

  def unsignedRightShift(left: A, right: A): A
  def and(left: A, right: A): A
  def or(left: A, right: A): A
  def xor(left: A, right: A): A

  def unary(op: UnaryBitwiseOperator, value: A): A =
    op match {
      case UnaryBitwiseOperator.BitwiseNeg => bitwiseNegate(value)
    }

  def binary(op: BinaryBitwiseOperator, left: A, right: A): A =
    op match {
      case BinaryBitwiseOperator.LeftShift          => leftShift(left, right)
      case BinaryBitwiseOperator.RightShift         => rightShift(left, right)
      case BinaryBitwiseOperator.UnsignedRightShift => unsignedRightShift(left, right)
      case BinaryBitwiseOperator.And                => and(left, right)
      case BinaryBitwiseOperator.Or                 => or(left, right)
      case BinaryBitwiseOperator.Xor                => xor(left, right)
    }
}

object BitwiseNumeric {

  private val shortCase: Schema.Case[Numeric.NumericShort.type, BitwiseNumeric[Any]] =
    Schema.Case[Numeric.NumericShort.type, BitwiseNumeric[Any]](
      "NumericShort",
      Schema.singleton[Numeric.NumericShort.type](Numeric.NumericShort),
      _.asInstanceOf[Numeric.NumericShort.type]
    )

  private val longCase: Schema.Case[Numeric.NumericLong.type, BitwiseNumeric[Any]] =
    Schema.Case[Numeric.NumericLong.type, BitwiseNumeric[Any]](
      "NumericLong",
      Schema.singleton[Numeric.NumericLong.type](Numeric.NumericLong),
      _.asInstanceOf[Numeric.NumericLong.type]
    )

  private val bigIntCase: Schema.Case[Numeric.NumericBigInt.type, BitwiseNumeric[Any]] =
    Schema.Case[Numeric.NumericBigInt.type, BitwiseNumeric[Any]](
      "NumericBigInt",
      Schema.singleton[Numeric.NumericBigInt.type](Numeric.NumericBigInt),
      _.asInstanceOf[Numeric.NumericBigInt.type]
    )

  private val intCase: Schema.Case[NumericInt.type, BitwiseNumeric[Any]] =
    Schema.Case[NumericInt.type, BitwiseNumeric[Any]](
      "NumericInt",
      Schema.singleton[NumericInt.type](NumericInt),
      _.asInstanceOf[NumericInt.type]
    )

  implicit val schema: Schema[BitwiseNumeric[Any]] =
    Schema.EnumN(
      CaseSet
        .Cons(
          shortCase,
          CaseSet.Empty[BitwiseNumeric[Any]]()
        )
        .:+:(longCase)
        .:+:(bigIntCase)
        .:+:(intCase)
    )
}

sealed trait Fractional[A] extends Numeric[A] {
  def fromDouble(const: Double): A

  def sin(a: A): A

  def cos(a: A): A

  def asin(a: A): A

  def acos(a: A): A

  def tan(a: A): A

  def atan(a: A): A

  def pow(left: A, right: A): A

  def log(left: A): A

  def floor(left: A): A

  def ceil(left: A): A

  def round(left: A): A

  def unary(operator: UnaryFractionalOperator, value: A): A =
    operator match {
      case UnaryFractionalOperator.Sin    => sin(value)
      case UnaryFractionalOperator.Cos    => cos(value)
      case UnaryFractionalOperator.ArcSin => asin(value)
      case UnaryFractionalOperator.ArcCos => acos(value)
      case UnaryFractionalOperator.Tan    => tan(value)
      case UnaryFractionalOperator.ArcTan => atan(value)
      case UnaryFractionalOperator.Floor  => floor(value)
      case UnaryFractionalOperator.Ceil   => ceil(value)
      case UnaryFractionalOperator.Round  => round(value)
      case UnaryFractionalOperator.Log    => log(value)
    }

  def binary(operator: BinaryFractionalOperator, left: A, right: A): A =
    operator match {
      case BinaryFractionalOperator.Pow => pow(left, right)
    }
}

object Fractional {

  implicit case object FractionalFloat extends Numeric.NumericFloat with Fractional[Float] {
    def fromDouble(const: Double): Float = const.toFloat

    override def sin(a: Float): Float = Math.sin(a.toDouble).toFloat
    override def cos(a: Float): Float = Math.cos(a.toDouble).toFloat

    override def asin(a: Float): Float = Math.asin(a.toDouble).toFloat
    override def acos(a: Float): Float = Math.acos(a.toDouble).toFloat

    override def tan(a: Float): Float  = Math.tan(a.toDouble).toFloat
    override def atan(a: Float): Float = Math.atan(a.toDouble).toFloat

    override def floor(left: Float): Float = Math.floor(left.toDouble).toFloat

    override def ceil(left: Float): Float = Math.ceil(left.toDouble).toFloat

    override def round(left: Float): Float = Math.round(left).toFloat

    override def pow(left: Float, right: Float): Float = Math.pow(left.toDouble, right.toDouble).toFloat

    override def log(left: Float): Float = Math.log(left.toDouble).toFloat

  }

  implicit case object FractionalDouble extends Numeric.NumericDouble with Fractional[Double] {

    def fromDouble(const: Double): Double = const.toDouble

    override def sin(a: Double): Double = Math.sin(a)
    override def cos(a: Double): Double = Math.cos(a)

    override def asin(a: Double): Double = Math.asin(a)
    override def acos(a: Double): Double = Math.acos(a)

    override def tan(a: Double): Double  = Math.tan(a)
    override def atan(a: Double): Double = Math.atan(a)

    override def floor(left: Double): Double = Math.floor(left)

    override def ceil(left: Double): Double = Math.ceil(left)

    override def round(left: Double): Double = left.round.toDouble

    override def pow(left: Double, right: Double): Double = Math.pow(left, right)

    override def log(left: Double): Double = Math.log(left)

  }

  implicit case object FractionalBigDecimal extends Numeric.NumericBigDecimal with Fractional[BigDecimal] {
    def fromDouble(const: Double): BigDecimal = BigDecimal(const)

    override def sin(a: BigDecimal): BigDecimal = Math.sin(a.doubleValue)
    override def cos(a: BigDecimal): BigDecimal = Math.cos(a.doubleValue)

    override def asin(a: BigDecimal): BigDecimal = Math.asin(a.doubleValue)
    override def acos(a: BigDecimal): BigDecimal = Math.acos(a.doubleValue)

    override def tan(a: BigDecimal): BigDecimal  = Math.tan(a.doubleValue)
    override def atan(a: BigDecimal): BigDecimal = Math.atan(a.doubleValue)

    override def floor(left: BigDecimal): BigDecimal = Math.floor(left.doubleValue)

    override def ceil(left: BigDecimal): BigDecimal = Math.ceil(left.doubleValue)

    override def round(left: BigDecimal): BigDecimal = Math.round(left.doubleValue)

    override def pow(left: BigDecimal, right: BigDecimal): BigDecimal =
      BigDecimal(
        Math.pow(left.doubleValue, right.doubleValue)
      )

    override def log(left: BigDecimal): BigDecimal =
      Math.log(left.doubleValue)

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
      CaseSet
        .Cons(
          floatCase,
          CaseSet.Empty[Fractional[Any]]()
        )
        .:+:(doubleCase)
        .:+:(bigDecimalCase)
    )
}
