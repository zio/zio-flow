/*
 * Copyright 2019-2021 John A. De Goes and the ZIO Contributors
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
import zio.schema._

/**
 * A `RemotePrism[S, A]` knows how to access a case `A` of a sum type `S` in the
 * context of a remote value.
 */
sealed trait RemotePrism[S, A] { self =>

  /**
   * The schema describing the structure of the type `A` being accessed by this
   * prism.
   */
  def schemaPiece: Schema[A]

  /**
   * The schema describing the structure of the type `S` being access by this
   * prism.
   */
  def schemaWhole: Schema[S]

  /**
   * Gets the case of the specified sum type if it exists or returns `None`
   * otherwise.
   */
  def get(s: Remote[S]): Remote[Option[A]] =
    Remote.PrismGet(s, self)

  /**
   * Views the specified case as an instance of the sum type.
   */
  def set(a: Remote[A]): Remote[S] =
    Remote.PrismSet(a, self)

  /**
   * Gets the case of the specified sum type if it exists or returns `None`
   * otherwise outside the context of a remote value.
   */
  private[flow] def unsafeGet(s: S): Option[A]

  /**
   * Views the specified case as an instance of the sum type outside the context
   * of a remote value.
   */
  private[flow] def unsafeSet(a: A): S
}

object RemotePrism {

  /**
   * Unsafely constructs a remote prism from a description of the structure of
   * the sum type and case. The caller is responsible for ensuring that the case
   * is one of the cases of the sum type.
   */
  private[remote] def unsafeMake[S, A](product: Schema.Enum[S], term: Schema.Case[A, S]): RemotePrism[S, A] =
    Primitive(product, term)

  /**
   * A remote prism formed directly from the structure of a sum type and a case
   * of that sum type.
   */
  private final case class Primitive[S, A](product: Schema.Enum[S], term: Schema.Case[A, S]) extends RemotePrism[S, A] {
    val schemaPiece = term.codec
    val schemaWhole = product

    def unsafeGet(s: S): Option[A] =
      term.deconstruct(s)

    def unsafeSet(a: A): S =
      a.asInstanceOf[S]
  }
}
