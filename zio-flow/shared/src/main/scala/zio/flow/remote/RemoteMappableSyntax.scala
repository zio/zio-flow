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

package zio.flow.remote

import zio.flow.Mappable

class RemoteMappableSyntax[A](self: Remote[A]) {

  def filter[F[_], A1](
    predicate: Remote[A1] => Remote[Boolean]
  )(implicit ev: A <:< F[A1], impl: Mappable[F]): Remote[F[A1]] =
    impl.performFilter(self.widen[F[A1]], predicate)

  def map[F[_], A1, B](f: Remote[A1] => Remote[B])(implicit ev: A <:< F[A1], impl: Mappable[F]): Remote[F[B]] =
    impl.performMap(self.widen[F[A1]], f)

  def flatMap[F[_], A1, B](f: Remote[A1] => Remote[F[B]])(implicit ev: A <:< F[A1], impl: Mappable[F]): Remote[F[B]] =
    impl.performFlatmap(self.widen[F[A1]], f)
}
