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

import zio.{Chunk, ChunkBuilder}
import zio.flow._
import zio.schema._

/**
 * A `RemoteTraversal[S, A]` knows how to access zero or more elements of a
 * collection type `S` in the context of a remote value.
 */
sealed trait RemoteTraversal[S, A] { self =>

  /**
   * The schema describing the structure of the type `A` being accessed by this
   * traversal.
   */
  def schemaPiece: Schema[A]

  /**
   * The schema describing the structure of the type `S` being access by this
   * lens.
   */
  def schemaWhole: Schema[S]

  /**
   * Gets the elements of the specified collection type.
   */
  def get(s: Remote[S]): Remote[Chunk[A]] =
    Remote.TraversalGet(s, self)

  /**
   * Sets the elements of the specified collection type to the specified values.
   */
  def set(s: Remote[S])(as: Remote[Chunk[A]]): Remote[S] =
    Remote.TraversalSet(s, as, self)

  /**
   * Updates the values of the elements of the specified collection type with
   * the specified function.
   */
  def update(s: Remote[S])(f: Remote[Chunk[A]] => Remote[Chunk[A]]): Remote[S] =
    set(s)(f(get(s)))

  /**
   * Gets the elements of the specified collection type outside the context of a
   * remote value.
   */
  private[flow] def unsafeGet(s: S): Chunk[A]

  /**
   * Sets the elements of the specified collection type to the specified values
   * outside the context of a remote value.
   */
  private[flow] def unsafeSet(s: S)(as: Chunk[A]): S
}

object RemoteTraversal {

  /**
   * Unsafely constructs a remote traversal from a description of the structure
   * of the collection and element types. The caller is responsible for ensuring
   * that the element type corresponds to the collection type.
   */
  def unsafeMake[S, A](collection: Schema.Collection[S, A], element: Schema[A]): RemoteTraversal[S, A] =
    Primitive(collection, element)

  /**
   * A remote lens formed directly from the structure of a collection type and
   * element type.
   */
  private final case class Primitive[S, A](collection: Schema.Collection[S, A], element: Schema[A])
      extends RemoteTraversal[S, A] {
    val schemaPiece: Schema[A] = element
    val schemaWhole: Schema[S] = collection

    def unsafeGet(s: S): Chunk[A] =
      (collection.asInstanceOf[Schema.Collection[_, _]]) match {
        case sequence @ Schema.Sequence(_, _, _, _, _) =>
          sequence.asInstanceOf[Schema.Sequence[S, A, _]].toChunk(s)
        case Schema.MapSchema(_, _, _) =>
          Chunk.fromIterable(s.asInstanceOf[Map[_, _]]).asInstanceOf[Chunk[A]]
      }

    def unsafeSet(s: S)(as: Chunk[A]): S =
      (collection.asInstanceOf[Schema.Collection[_, _]]) match {
        case sequence @ Schema.Sequence(_, _, _, _, _) =>
          val builder       = ChunkBuilder.make[A]()
          val leftIterator  = sequence.asInstanceOf[Schema.Sequence[S, A, _]].toChunk(s).iterator
          val rightIterator = as.iterator
          while (leftIterator.hasNext && rightIterator.hasNext) {
            val _ = leftIterator.next()
            builder += rightIterator.next()
          }
          while (leftIterator.hasNext)
            builder += leftIterator.next()
          sequence.asInstanceOf[Schema.Sequence[S, A, _]].fromChunk(builder.result())
        case Schema.MapSchema(_, _, _) =>
          (s.asInstanceOf[Map[_, _]] ++ as).asInstanceOf[S]
      }
  }
}
