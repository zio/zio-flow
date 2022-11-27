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
import zio.Chunk

final class RemoteChunkCompanionSyntax(val self: Chunk.type) extends AnyVal {
  def fill[A](n: Remote[Int])(elem: Remote[A]): Remote[Chunk[A]] =
    fromList(List.fill(n)(elem))

  def fromList[A](list: Remote[List[A]]): Remote[Chunk[A]] =
    // NOTE: We take advantage of the fact that List and Chunk are represented the same on the DynamicValue level.
    //       A safer implementation in the future would use RemoteOptic.Traversal to convert from/to lists but that
    //       is currently broken.
    list.asInstanceOf[Remote[Chunk[A]]]
}
