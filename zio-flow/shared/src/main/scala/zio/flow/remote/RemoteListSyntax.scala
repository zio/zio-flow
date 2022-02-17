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

import zio.flow.Remote.RemoteFunction
import zio.flow._
import zio.schema.Schema

class RemoteListSyntax[A](val self: Remote[List[A]]) {

  def ++(
    other: Remote[List[A]]
  )(implicit schemaA: Schema[A], schemaB: Schema[List[A]]): Remote[List[A]] = {
    val reversedSelf: Remote[List[A]] = reverse
    reversedSelf.fold(other)((l, a) => Remote.Cons(l, a))
  }

  def reverse(implicit schemaA: Schema[A], schemaB: Schema[List[A]]): Remote[List[A]] =
    fold[List[A]](Remote(Nil))((l, a) => Remote.Cons(l, a))

  def take(num: Remote[Int])(implicit schemaA: Schema[A]): Remote[List[A]] = {
    val ifTrue: Remote[List[A]] =
      Remote
        .UnCons(self.widen[List[A]])
        .widen[Option[(A, List[A])]]
        .handleOption(Nil, (tuple: Remote[(A, List[A])]) => Remote.Cons(tuple._2.take(num - Remote(1)), tuple._1))
    (num > Remote(0)).ifThenElse(ifTrue, Nil)
  }

  def takeWhile(
    predicate: Remote[A] => Remote[Boolean]
  )(implicit schemaA: Schema[A]): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .handleOption(
        Remote(Nil),
        (tuple: Remote[(A, List[A])]) =>
          predicate(tuple._1).ifThenElse(Remote.Cons(tuple._2.takeWhile(predicate), tuple._1), Nil)
      )

  def drop(num: Remote[Int])(implicit schemaA: Schema[A]): Remote[List[A]] = {
    val ifTrue =
      Remote
        .UnCons(self)
        .widen[Option[(A, List[A])]]
        .handleOption(Nil, (tuple: Remote[(A, List[A])]) => tuple._2.drop(num - Remote(1)))

    (num > Remote(0)).ifThenElse(ifTrue, self)
  }

  def dropWhile(
    predicate: Remote[A] => Remote[Boolean]
  )(implicit schemaA: Schema[A]): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .handleOption(Remote(Nil), (tuple: Remote[(A, List[A])]) => tuple._2.dropWhile(predicate))

  final def fold[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[B] =
    Remote.Fold(self, initial, RemoteFunction((tuple: Remote[(B, A)]) => f(tuple._1, tuple._2)))

  final def headOption1(implicit schemaA: Schema[A]): Remote[Option[A]] = Remote
    .UnCons(self)
    .widen[Option[(A, List[A])]]
    .handleOption[Option[A]](Remote(None), tuple => Remote.Some0(tuple._1))

  final def headOption(implicit
    schemaA: Schema[A],
    schemaB: Schema[Option[A]]
  ): Remote[Option[A]] =
    fold[Option[A]](Remote(None))((remoteOptionA, a) =>
      remoteOptionA.isSome.ifThenElse(remoteOptionA.self, Remote.Some0(a))
    )

  final def length(implicit schemaA: Schema[A], schemaB: Schema[Int]): Remote[Int] =
    self.fold[Int](0)((len, _) => len + 1)

  final def product(implicit numeric: Numeric[A], schemaB: Schema[A]): Remote[A] =
    fold[A](numeric.fromLong(1L))(_ * _)

  final def sum(implicit numeric: Numeric[A], schemaB: Schema[A]): Remote[A] =
    fold[A](numeric.fromLong(0L))(_ + _)

  final def filter(
    predicate: Remote[A] => Remote[Boolean]
  )(implicit schemaA: Schema[A], schemaB: Schema[List[A]]): Remote[List[A]] =
    fold[List[A]](Remote(Nil))((a2: Remote[List[A]], a1: Remote[A]) =>
      predicate(a1).ifThenElse(Remote.Cons(a2, a1), a2)
    )

  final def isEmpty(implicit schemaA: Schema[A], schemaB: Schema[Option[A]]): Remote[Boolean] =
    self.headOption.isNone.ifThenElse(Remote(true), Remote(false))
}
