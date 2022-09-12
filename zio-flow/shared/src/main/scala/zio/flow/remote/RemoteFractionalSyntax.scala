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

final class RemoteFractionalSyntax[A](private val self: Remote[A]) extends AnyVal {
  def floor(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Floor))

  def ceil(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Ceil))

  def round(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.Round))

  def toRadians(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.ToRadians))

  def toDegrees(implicit fractional: Fractional[A]): Remote[A] =
    Remote.Unary(self, UnaryOperators(UnaryFractionalOperator.ToDegrees))

  def isNaN(implicit fractional: Fractional[A]): Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(FractionalPredicateOperator.IsNaN))

  def isInfinity(implicit fractional: Fractional[A]): Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(FractionalPredicateOperator.IsInfinity))

  def isInfinite(implicit fractional: Fractional[A]): Remote[Boolean] = isInfinity

  def isFinite(implicit fractional: Fractional[A]): Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(FractionalPredicateOperator.IsFinite))

  def isPosInfinity(implicit fractional: Fractional[A]): Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(FractionalPredicateOperator.IsPosInfinity))

  def isNegInfinity(implicit fractional: Fractional[A]): Remote[Boolean] =
    Remote.Unary(self, UnaryOperators(FractionalPredicateOperator.IsNegInifinty))
}
