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

import zio.flow.Remote
import zio.flow.remote.numeric._

package object math {
  val E: Remote[Double]  = Remote(scala.math.E)
  val Pi: Remote[Double] = Remote(scala.math.Pi)

  def sin[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Sin))

  def cos[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Cos))

  def tan[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Tan))

  def asin[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.ArcSin))

  def acos[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.ArcCos))

  def atan[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.ArcTan))

  def toRadians[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    value.toRadians

  def toDegrees[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    value.toDegrees

  def atan2[A](y: Remote[A], x: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(y, x, BinaryOperators(BinaryFractionalOperator.ArcTan2))

  def hypot[A](x: Remote[A], y: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryFractionalOperator.Hypot))

  def ceil[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Ceil))

  def floor[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Floor))

  def rint[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Rint))

  def round[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Round))

  def abs[A](value: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    value.abs

  def max[A](x: Remote[A], y: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    x.max(y)

  def min[A](x: Remote[A], y: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    x.min(y)

  def signum[A](value: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    value.sign

  def floorDiv[A](x: Remote[A], y: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryIntegralOperator.FloorDiv))

  def floorMod[A](x: Remote[A], y: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryIntegralOperator.FloorMod))

  def copySign[A](magnitude: Remote[A], sign: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(magnitude, sign, BinaryOperators(BinaryFractionalOperator.CopySign))

  def nextAfter[A](start: Remote[A], direction: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(start, direction, BinaryOperators(BinaryFractionalOperator.NextAfter))

  def nextUp[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.NextUp))

  def nextDown[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.NextDown))

  def scalb[A](x: Remote[A], y: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryFractionalOperator.Scalb))

  def sqrt[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Sqrt))

  def cbrt[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Cbrt))

  def pow[A](x: Remote[A], y: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryFractionalOperator.Pow))

  def exp[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Exp))

  def expm1[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Expm1))

  def getExponent[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[Int] =
    Remote.Unary(value, UnaryOperators.Conversion(RemoteConversions.FractionalGetExponent(fractional)))

  def log[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Log))

  def log1p[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Log1p))

  def log10[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Log10))

  def sinh[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Sinh))

  def cosh[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Cosh))

  def tanh[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Tanh))

  def ulp[A](value: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryFractionalOperator.Ulp))

  def IEEEremainder[A](x: Remote[A], y: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryFractionalOperator.IEEEremainder))

  def addExact[A](x: Remote[A], y: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryIntegralOperator.AddExact))

  def subtractExact[A](x: Remote[A], y: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryIntegralOperator.SubExact))

  def multiplyExact[A](x: Remote[A], y: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Binary(x, y, BinaryOperators(BinaryIntegralOperator.MulExact))

  def incrementExact[A](x: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Unary(x, UnaryOperators(UnaryIntegralOperator.IncExact))

  def decrementExact[A](x: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Unary(x, UnaryOperators(UnaryIntegralOperator.DecExact))

  def negateExact[A](value: Remote[A])(implicit integral: Integral[A]): Remote[A] =
    Remote.Unary(value, UnaryOperators(UnaryIntegralOperator.NegExact))
}
