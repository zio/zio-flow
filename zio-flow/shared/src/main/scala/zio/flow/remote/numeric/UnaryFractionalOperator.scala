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

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryFractionalOperator
object UnaryFractionalOperator {
  case object Sin       extends UnaryFractionalOperator
  case object Cos       extends UnaryFractionalOperator
  case object ArcSin    extends UnaryFractionalOperator
  case object ArcCos    extends UnaryFractionalOperator
  case object Tan       extends UnaryFractionalOperator
  case object ArcTan    extends UnaryFractionalOperator
  case object Floor     extends UnaryFractionalOperator
  case object Ceil      extends UnaryFractionalOperator
  case object Round     extends UnaryFractionalOperator
  case object ToRadians extends UnaryFractionalOperator
  case object ToDegrees extends UnaryFractionalOperator
  case object Rint      extends UnaryFractionalOperator
  case object NextUp    extends UnaryFractionalOperator
  case object NextDown  extends UnaryFractionalOperator
  case object Sqrt      extends UnaryFractionalOperator
  case object Cbrt      extends UnaryFractionalOperator
  case object Exp       extends UnaryFractionalOperator
  case object Expm1     extends UnaryFractionalOperator
  case object Log       extends UnaryFractionalOperator
  case object Log1p     extends UnaryFractionalOperator
  case object Log10     extends UnaryFractionalOperator
  case object Sinh      extends UnaryFractionalOperator
  case object Cosh      extends UnaryFractionalOperator
  case object Tanh      extends UnaryFractionalOperator
  case object Ulp      extends UnaryFractionalOperator

  implicit val schema: Schema[UnaryFractionalOperator] = DeriveSchema.gen
}
