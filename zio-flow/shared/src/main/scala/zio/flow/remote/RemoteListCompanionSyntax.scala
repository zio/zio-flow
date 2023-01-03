/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

final class RemoteListCompanionSyntax(val self: List.type) extends AnyVal {
  def fill[A](n: Remote[Int])(elem: Remote[A]): Remote[List[A]] =
    Remote
      .recurseSimple((n, Remote.nil[A])) { case (input, rec) =>
        val current = input._1
        val lst     = input._2
        (current === 0).ifThenElse(
          ifTrue = (current, lst),
          ifFalse = rec((current - 1, elem :: lst))
        )
      }
      ._2
}
