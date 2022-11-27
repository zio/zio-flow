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

import zio.flow._
import zio.flow.remote.numeric._

final class RemoteNumericSyntax[A](private val self: Remote[A]) extends AnyVal {

  def +(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryNumericOperator.Add))

  def /(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryNumericOperator.Div))

  def %(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryNumericOperator.Mod))

  def *(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryNumericOperator.Mul))

  def unary_-(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryNumericOperator.Neg))

  def unary_~(implicit numeric: Integral[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryIntegralOperator.BitwiseNeg))

  def -(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryNumericOperator.Sub))

  def abs(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryNumericOperator.Abs))

  def sign(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryNumericOperator.Sign))

  def min(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryNumericOperator.Min))

  def max(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryNumericOperator.Max))

  def >>(that: Remote[A])(implicit bitwiseNumeric: Integral[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryIntegralOperator.RightShift))

  def <<(that: Remote[A])(implicit bitwiseNumeric: Integral[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryIntegralOperator.LeftShift))

  def >>>(that: Remote[A])(implicit bitwiseNumeric: Integral[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryIntegralOperator.UnsignedRightShift))

  def &(that: Remote[A])(implicit bitwiseNumeric: Integral[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryIntegralOperator.And))

  def |(that: Remote[A])(implicit bitwiseNumeric: Integral[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryIntegralOperator.Or))

  def ^(that: Remote[A])(implicit bitwiseNumeric: Integral[A]): Remote[A] =
    Remote.Binary(self, that, BinaryOperators(BinaryIntegralOperator.Xor))

  def toInt(implicit numeric: Numeric[A]): Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToInt(numeric)))

  def toChar(implicit numeric: Numeric[A]): Remote[Char] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToChar(numeric)))

  def toByte(implicit numeric: Numeric[A]): Remote[Byte] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToByte(numeric)))

  def toShort(implicit numeric: Numeric[A]): Remote[Short] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToShort(numeric)))

  def toLong(implicit numeric: Numeric[A]): Remote[Long] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToLong(numeric)))

  def toFloat(implicit numeric: Numeric[A]): Remote[Float] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToFloat(numeric)))

  def toDouble(implicit numeric: Numeric[A]): Remote[Double] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToDouble(numeric)))

  def intValue(implicit numeric: Numeric[A]): Remote[Int]       = toInt
  def shortValue(implicit numeric: Numeric[A]): Remote[Short]   = toShort
  def longValue(implicit numeric: Numeric[A]): Remote[Long]     = toLong
  def floatValue(implicit numeric: Numeric[A]): Remote[Float]   = toFloat
  def doubleValue(implicit numeric: Numeric[A]): Remote[Double] = toDouble

  def toBinaryString(implicit bitwiseNumeric: Integral[A]): Remote[String] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToBinaryString(bitwiseNumeric)))

  def toHexString(implicit bitwiseNumeric: Integral[A]): Remote[String] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToHexString(bitwiseNumeric)))

  def toOctalString(implicit bitwiseNumeric: Integral[A]): Remote[String] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.NumericToOctalString(bitwiseNumeric)))
}
