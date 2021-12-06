/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
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

package zio.flow

sealed trait CanFail[-E]

object CanFail extends CanFail[Any] {
  implicit def canFail[E]: CanFail[E] = CanFail

  /**
   * Providing multiple ambiguous values for CanFail[Nothing] makes it not compile when `E = Nothing`.
   * We need this because `CanFail`` should always have a legitimate error type `E` representing a ZFlow that can fail with that `error`.
   */

  implicit val canFailAmbiguous1: CanFail[Nothing] = CanFail
  implicit val canFailAmbiguous2: CanFail[Nothing] = CanFail
}
