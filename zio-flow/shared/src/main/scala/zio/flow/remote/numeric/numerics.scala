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
import zio.schema.{CaseSet, Schema}

sealed trait Numeric[A] {
  def schema: Schema[A]

  def fromLong(l: Long): Remote[A]

  def add(left: A, right: A): A

  def multiply(left: A, right: A): A

  def divide(left: A, right: A): A

  def mod(left: A, right: A): A

  def pow(left: A, right: A): A

  def negate(left: A): A

  def root(left: A, right: A): A

  def log(left: A, right: A): A

  def abs(left: A): A

  def min(left: A, right: A): A

  def max(left: A, right: A): A

  def floor(left: A): A

  def ceil(left: A): A

  def round(left: A): A

  def unary(op: UnaryNumericOperator, value: A): A =
    op match {
      case UnaryNumericOperator.Neg   => negate(value)
      case UnaryNumericOperator.Abs   => abs(value)
      case UnaryNumericOperator.Floor => floor(value)
      case UnaryNumericOperator.Ceil  => ceil(value)
      case UnaryNumericOperator.Round => round(value)
    }

  def binary(op: BinaryNumericOperator, left: A, right: A): A =
    op match {
      case BinaryNumericOperator.Add  => add(left, right)
      case BinaryNumericOperator.Mul  => multiply(left, right)
      case BinaryNumericOperator.Div  => divide(left, right)
      case BinaryNumericOperator.Mod  => mod(left, right)
      case BinaryNumericOperator.Pow  => pow(left, right)
      case BinaryNumericOperator.Root => root(left, right)
      case BinaryNumericOperator.Log  => log(left, right)
      case BinaryNumericOperator.Min  => min(left, right)
      case BinaryNumericOperator.Max  => max(left, right)
    }
}

object Numeric extends NumericImplicits0 {
  trait NumericInt extends Numeric[Int] {
    override def fromLong(l: Long): Remote[Int] = Remote(l.toInt)

    def add(left: Int, right: Int): Int = left + right

    override def multiply(left: Int, right: Int): Int = left * right

    override def divide(left: Int, right: Int): Int = left / right

    override def pow(left: Int, right: Int): Int = Math.pow(left.toDouble, right.toDouble).toInt

    override def root(left: Int, right: Int): Int = Math.pow(left.toDouble, 1 / right.toDouble).toInt

    override def log(left: Int, right: Int): Int = (Math.log(left.toDouble) / Math.log(right.toDouble)).toInt

    def schema: Schema[Int] = implicitly[Schema[Int]]

    override def negate(left: Int): Int = -1 * left

    def mod(left: Int, right: Int): Int = left % right

    override def abs(left: Int): Int = Math.abs(left)

    override def min(left: Int, right: Int): Int = Math.min(left, right)

    override def max(left: Int, right: Int): Int = Math.max(left, right)

    override def floor(left: Int): Int = Math.floor(left.toDouble).toInt

    override def ceil(left: Int): Int = Math.ceil(left.toDouble).toInt

    override def round(left: Int): Int = Math.round(left.toFloat)
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

  trait NumericShort extends Numeric[Short] {
    override def fromLong(l: Long): Remote[Short] = Remote(l.toShort)

    override def add(left: Short, right: Short): Short = (left + right).shortValue()

    override def multiply(left: Short, right: Short): Short = (left * right).shortValue()

    override def divide(left: Short, right: Short): Short = (left / right).shortValue()

    override def mod(left: Short, right: Short): Short = (left % right).shortValue()

    override def pow(left: Short, right: Short): Short = Math.pow(left.toDouble, right.toDouble).toShort

    override def root(left: Short, right: Short): Short = Math.pow(left.toDouble, 1 / right.toDouble).toShort

    override def log(left: Short, right: Short): Short = (Math.log(left.toDouble) / Math.log(right.toDouble)).toShort

    override def negate(left: Short): Short = (-1 * left).toShort

    def schema: Schema[Short] = implicitly[Schema[Short]]

    override def abs(left: Short): Short = Math.abs(left.toDouble).toShort

    override def min(left: Short, right: Short): Short = Math.min(left.toDouble, right.toDouble).toShort

    override def max(left: Short, right: Short): Short = Math.max(left.toDouble, right.toDouble).toShort

    override def floor(left: Short): Short = Math.floor(left.toDouble).toShort

    override def ceil(left: Short): Short = Math.ceil(left.toDouble).toShort

    override def round(left: Short): Short = Math.round(left.toDouble).toShort
  }
  implicit case object NumericShort extends NumericShort

  trait NumericLong extends Numeric[Long] {
    override def fromLong(l: Long): Remote[Long] = Remote(l)

    override def add(left: Long, right: Long): Long = left + right

    override def multiply(left: Long, right: Long): Long = left * right

    override def divide(left: Long, right: Long): Long = left / right

    override def mod(left: Long, right: Long): Long = left % right

    override def pow(left: Long, right: Long): Long = Math.pow(left.toDouble, right.toDouble).toLong

    override def root(left: Long, right: Long): Long = Math.pow(left.toDouble, 1 / right.toDouble).toLong

    override def negate(left: Long): Long = -1 * left

    override def log(left: Long, right: Long): Long = (Math.log(left.toDouble) / Math.log(right.toDouble)).toLong

    def schema: Schema[Long] = implicitly[Schema[Long]]

    override def abs(left: Long): Long = Math.abs(left)

    override def min(left: Long, right: Long): Long = Math.min(left, right)

    override def max(left: Long, right: Long): Long = Math.max(left, right)

    override def floor(left: Long): Long = Math.floor(left.toDouble).toLong

    override def ceil(left: Long): Long = Math.ceil(left.toDouble).toLong

    override def round(left: Long): Long = Math.round(left.toDouble)
  }
  implicit case object NumericLong extends NumericLong

  trait NumericBigInt extends Numeric[BigInt] {
    override def fromLong(l: Long): Remote[BigInt] = Remote(BigInt(l))

    override def add(left: BigInt, right: BigInt): BigInt = left + right

    override def multiply(left: BigInt, right: BigInt): BigInt = left * right

    override def divide(left: BigInt, right: BigInt): BigInt = left / right

    override def mod(left: BigInt, right: BigInt): BigInt = left % right

    override def pow(left: BigInt, right: BigInt): BigInt = BigInt(
      Math.pow(left.doubleValue, right.doubleValue).toInt
    )

    override def root(left: BigInt, right: BigInt): BigInt = BigInt(
      Math.pow(left.doubleValue, 1 / right.doubleValue).toInt
    )

    override def negate(left: BigInt): BigInt = -1 * left

    override def log(left: BigInt, right: BigInt): BigInt = BigInt(
      (Math.log(left.doubleValue) / Math.log(right.doubleValue)).toInt
    )

    def schema: Schema[BigInt] = implicitly[Schema[BigInt]]

    override def abs(left: BigInt): BigInt = Math.abs(left.toInt)

    override def min(left: BigInt, right: BigInt): BigInt = Math.min(left.toInt, right.toInt)

    override def max(left: BigInt, right: BigInt): BigInt = Math.max(left.toInt, right.toInt)

    override def floor(left: BigInt): BigInt = Math.floor(left.doubleValue).toInt

    override def ceil(left: BigInt): BigInt = Math.ceil(left.doubleValue).toInt

    override def round(left: BigInt): BigInt = Math.round(left.doubleValue)
  }
  implicit case object NumericBigInt extends NumericBigInt

  trait NumericFloat extends Numeric[Float] {
    override def fromLong(l: Long): Remote[Float] = Remote(l.toFloat)

    override def add(left: Float, right: Float): Float = left + right

    override def multiply(left: Float, right: Float): Float = left * right

    override def divide(left: Float, right: Float): Float = left / right

    override def mod(left: Float, right: Float): Float = left % right

    override def pow(left: Float, right: Float): Float = Math.pow(left.toDouble, right.toDouble).toFloat

    override def root(left: Float, right: Float): Float = Math.pow(left.toDouble, 1 / right.toDouble).toFloat

    override def negate(left: Float): Float = -1 * left

    override def log(left: Float, right: Float): Float = (Math.log(left.toDouble) / Math.log(right.toDouble)).toFloat

    def schema: Schema[Float] = implicitly[Schema[Float]]

    override def abs(left: Float): Float = Math.abs(left)

    override def min(left: Float, right: Float): Float = Math.min(left, right)

    override def max(left: Float, right: Float): Float = Math.max(left, right)

    override def floor(left: Float): Float = Math.floor(left.toDouble).toFloat

    override def ceil(left: Float): Float = Math.ceil(left.toDouble).toFloat

    override def round(left: Float): Float = Math.round(left).toFloat
  }
  implicit case object NumericFloat extends NumericFloat

  trait NumericDouble extends Numeric[Double] {
    override def fromLong(l: Long): Remote[Double] = Remote(l.toDouble)

    override def add(left: Double, right: Double): Double = left + right

    override def multiply(left: Double, right: Double): Double = left * right

    override def divide(left: Double, right: Double): Double = left / right

    override def mod(left: Double, right: Double): Double = left % right

    override def pow(left: Double, right: Double): Double = Math.pow(left, right)

    override def root(left: Double, right: Double): Double = Math.pow(left, 1 / right)

    override def negate(left: Double): Double = -1 * left

    override def log(left: Double, right: Double): Double = Math.log(left) / Math.log(right)

    def schema: Schema[Double] = implicitly[Schema[Double]]

    override def abs(left: Double): Double = Math.abs(left)

    override def min(left: Double, right: Double): Double = Math.min(left, right)

    override def max(left: Double, right: Double): Double = Math.max(left, right)

    override def floor(left: Double): Double = Math.floor(left)

    override def ceil(left: Double): Double = Math.ceil(left)

    override def round(left: Double): Double = Math.round(left).toDouble
  }
  implicit case object NumericDouble extends NumericDouble

  trait NumericBigDecimal extends Numeric[BigDecimal] {
    override def fromLong(l: Long): Remote[BigDecimal] = Remote(BigDecimal(l))

    override def add(left: BigDecimal, right: BigDecimal): BigDecimal = left + right

    override def multiply(left: BigDecimal, right: BigDecimal): BigDecimal = left * right

    override def divide(left: BigDecimal, right: BigDecimal): BigDecimal = left / right

    override def mod(left: BigDecimal, right: BigDecimal): BigDecimal = left % right

    override def pow(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      Math.pow(left.doubleValue, right.doubleValue)
    )

    override def root(left: BigDecimal, right: BigDecimal): BigDecimal =
      Math.pow(left.doubleValue, 1 / right.doubleValue)

    override def log(left: BigDecimal, right: BigDecimal): BigDecimal =
      Math.log(left.doubleValue) / Math.log(right.doubleValue)

    override def negate(left: BigDecimal): BigDecimal = -1 * left

    def schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]

    override def abs(left: BigDecimal): BigDecimal = Math.abs(left.doubleValue)

    override def min(left: BigDecimal, right: BigDecimal): BigDecimal = Math.min(left.doubleValue, right.doubleValue)

    override def max(left: BigDecimal, right: BigDecimal): BigDecimal = Math.max(left.doubleValue, right.doubleValue)

    override def floor(left: BigDecimal): BigDecimal = Math.floor(left.doubleValue)

    override def ceil(left: BigDecimal): BigDecimal = Math.ceil(left.doubleValue)

    override def round(left: BigDecimal): BigDecimal = Math.round(left.doubleValue)
  }
  implicit case object NumericBigDecimal extends NumericBigDecimal
}

sealed trait Fractional[A] extends Numeric[A] {
  def fromDouble(const: Double): A

  def schema: Schema[A]

  def sin(a: A): A

  def asin(a: A): A

  def atan(a: A): A

  def unary(operator: UnaryFractionalOperator, value: A): A =
    operator match {
      case UnaryFractionalOperator.Sin    => sin(value)
      case UnaryFractionalOperator.ArcSin => asin(value)
      case UnaryFractionalOperator.ArcTan => atan(value)
    }
}

object Fractional {

  implicit case object FractionalFloat extends Numeric.NumericFloat with Fractional[Float] {
    def fromDouble(const: Double): Float = const.toFloat

    override def sin(a: Float): Float = Math.sin(a.toDouble).toFloat

    override def asin(a: Float): Float = Math.asin(a.toDouble).toFloat

    override def atan(a: Float): Float = Math.atan(a.toDouble).toFloat
  }

  implicit case object FractionalDouble extends Numeric.NumericDouble with Fractional[Double] {

    def fromDouble(const: Double): Double = const.toDouble

    override def sin(a: Double): Double = Math.sin(a)

    override def asin(a: Double): Double = Math.asin(a)

    override def atan(a: Double): Double = Math.atan(a)

  }

  implicit case object FractionalBigDecimal extends Numeric.NumericBigDecimal with Fractional[BigDecimal] {
    def fromDouble(const: Double): BigDecimal = BigDecimal(const)

    override def sin(a: BigDecimal): BigDecimal = Math.sin(a.doubleValue)

    override def asin(a: BigDecimal): BigDecimal = Math.asin(a.doubleValue)

    override def atan(a: BigDecimal): BigDecimal =
      Math.atan(a.doubleValue)

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
