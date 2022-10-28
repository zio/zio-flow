/*
 * Copyright 2019-2022 John A. De Goes and the ZIO Contributors
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

import zio.schema._

/**
 * A `RemoteAccessorBuilder` knows how to build accessors for remote
 * descriptions of sum and product types. This allows easily getting or setting
 * fields of user defined product types or fields of user defined sum types.
 */
object RemoteAccessorBuilder extends AccessorBuilder {

  override type Lens[F, S, A]   = RemoteOptic.Lens[F, S, A]
  override type Prism[F, S, A]  = RemoteOptic.Prism[F, S, A]
  override type Traversal[S, A] = RemoteOptic.Traversal[S, A]

  override def makeLens[F, S, A](product: Schema.Record[S], term: Schema.Field[A]): RemoteOptic.Lens[F, S, A] =
    RemoteOptic.Lens(term.label)

  override def makePrism[F, S, A](sum: Schema.Enum[S], term: Schema.Case[A, S]): RemoteOptic.Prism[F, S, A] =
    RemoteOptic.Prism(sum.id, term.id)

  override def makeTraversal[S, A](
    collection: Schema.Collection[S, A],
    element: Schema[A]
  ): RemoteOptic.Traversal[S, A] =
    RemoteOptic.Traversal()
}
