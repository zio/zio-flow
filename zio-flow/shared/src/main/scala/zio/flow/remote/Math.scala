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
import zio.flow.remote.numeric.{BinaryFractionalOperator, Fractional, UnaryFractionalOperator}

object Math {
  def sin[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Sin))

  def cos[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Cos))

  def tan[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Tan))

  def asin[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.ArcSin))

  def acos[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.ArcCos))

  def atan[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.ArcTan))

  def pow[A](self: Remote[A], exp: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Binary(self, exp, BinaryOperators(BinaryFractionalOperator.Pow))

  def log[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Log))

  def floor[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Floor))

  def ceil[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Ceil))

  def round[A](self: Remote[A])(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Round))
}
