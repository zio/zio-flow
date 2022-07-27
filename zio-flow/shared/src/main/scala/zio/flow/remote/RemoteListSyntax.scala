///*
// * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */

package zio.flow.remote

import zio.flow.Remote.EvaluatedRemoteFunction
import zio.flow._
import zio.flow.remote.numeric._

final class RemoteListSyntax[A](val self: Remote[List[A]]) extends AnyVal {

  def ++(other: Remote[List[A]]): Remote[List[A]] = {
    val reversedSelf: Remote[List[A]] = reverse
    reversedSelf.fold(other)((l, a) => Remote.Cons(l, a))
  }

  def reverse: Remote[List[A]] =
    fold[List[A]](Remote.nil)((l, a) => Remote.Cons(l, a))

  def take(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue: Remote[List[A]] =
      Remote
        .UnCons(self.widen[List[A]])
        .widen[Option[(A, List[A])]]
        .fold(Remote.nil[A], (tuple: Remote[(A, List[A])]) => Remote.Cons(tuple._2.take(num - Remote(1)), tuple._1))
    (num > Remote(0)).ifThenElse(ifTrue, Remote.nil[A])
  }

  def takeWhile(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .fold(
        Remote.nil[A],
        (tuple: Remote[(A, List[A])]) =>
          predicate(tuple._1).ifThenElse(Remote.Cons(tuple._2.takeWhile(predicate), tuple._1), Remote.nil)
      )

  def drop(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue =
      Remote
        .UnCons(self)
        .widen[Option[(A, List[A])]]
        .fold(Remote.nil, (tuple: Remote[(A, List[A])]) => tuple._2.drop(num - Remote(1)))

    (num > Remote(0)).ifThenElse(ifTrue, self)
  }

  def dropWhile(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .fold(Remote.nil[A], (tuple: Remote[(A, List[A])]) => tuple._2.dropWhile(predicate))

  def fold[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    Remote.Fold(self, initial, EvaluatedRemoteFunction.make((tuple: Remote[(B, A)]) => f(tuple._1, tuple._2)))

  def headOption1: Remote[Option[A]] = Remote
    .UnCons(self)
    .widen[Option[(A, List[A])]]
    .fold[Option[A]](Remote.none[A], tuple => Remote.RemoteSome(tuple._1))

  def headOption: Remote[Option[A]] =
    fold[Option[A]](Remote.none[A])((remoteOptionA, a) =>
      remoteOptionA.isSome.ifThenElse(remoteOptionA.self, Remote.RemoteSome(a))
    )

  def length: Remote[Int] =
    self.fold[Int](0)((len, _) => len + 1)

  def product(implicit numeric: Numeric[A]): Remote[A] =
    fold[A](numeric.fromLong(1L))(_ * _)

  def sum(implicit numeric: Numeric[A]): Remote[A] =
    fold[A](numeric.fromLong(0L))(_ + _)

  def filter(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    fold[List[A]](Remote.nil[A])((a2: Remote[List[A]], a1: Remote[A]) =>
      predicate(a1).ifThenElse(Remote.Cons(a2, a1), a2)
    )

  def isEmpty: Remote[Boolean] =
    self.headOption.isNone.ifThenElse(Remote(true), Remote(false))
}
