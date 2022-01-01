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

class RemoteNumericSyntax[A](self: Remote[A]) {

  final def +(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.AddNumeric(self.widen[A], that, numeric)

  final def /(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.DivNumeric(self.widen[A], that, numeric)

  final def *(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.MulNumeric(self.widen[A], that, numeric)

  final def unary_-(implicit numeric: Numeric[A]): Remote[A] =
    Remote.NegationNumeric(self.widen[A], numeric)

  final def -(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.AddNumeric(self.widen[A], -that, numeric)

  final def pow(exp: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.PowNumeric(self.widen[A], exp, numeric)

  final def root(n: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.RootNumeric(self.widen[A], n, numeric)

  final def log(base: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.LogNumeric(self.widen[A], base, numeric)

  final def mod(that: Remote[Int])(implicit ev: A <:< Int, numericInt: Numeric[Int]): Remote[Int] =
    Remote.ModNumeric(self.widen[Int], that)

  final def abs(implicit numeric: Numeric[A]): Remote[A] =
    Remote.AbsoluteNumeric(self.widen[A], numeric)

  final def min(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.MinNumeric(self.widen[A], that, numeric)

  final def max(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.MaxNumeric(self.widen[A], that, numeric)

  final def floor(implicit numeric: Numeric[A]): Remote[A] =
    Remote.FloorNumeric(self.widen[A], numeric)

  final def ceil(implicit numeric: Numeric[A]): Remote[A] =
    Remote.CeilNumeric(self.widen[A], numeric)

  final def round(implicit numeric: Numeric[A]): Remote[A] =
    Remote.RoundNumeric(self.widen[A], numeric)
}
