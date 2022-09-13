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

import zio.flow.Remote.UnboundRemoteFunction
import zio.flow._
import zio.flow.remote.numeric._

final class RemoteListSyntax[A](val self: Remote[List[A]]) extends AnyVal {
  // TODO: primitive concat?

  def ++(suffix: Remote[List[A]]): Remote[List[A]] =
    reverse.foldLeft(suffix)((l, a) => Remote.Cons(l, a))

  def ++:(prefix: Remote[List[A]]): Remote[List[A]] =
    prefix ++ self

  def +:[B >: A](elem: Remote[B]): Remote[List[B]] =
    elem :: self

  def :+[B >: A](elem: Remote[B]): Remote[List[B]] =
    Remote.Cons(reverse.widen[List[B]], elem).reverse

  def :++[B >: A](suffix: Remote[List[B]]): Remote[List[B]] =
    self.widen[List[B]] ++ suffix

  def ::[B >: A](elem: Remote[B]): Remote[List[B]] =
    Remote.Cons(self.widen[List[B]], elem)

  def :::[B >: A](prefix: Remote[List[B]]): Remote[List[B]] =
    prefix ++ self.widen[List[B]]

  def appended[B >: A](elem: Remote[B]): Remote[List[B]] =
    self :+ elem

  def appendedAll[B >: A](suffix: Remote[List[B]]): Remote[List[B]] =
    self :++ suffix

  def apply(n: Remote[Int]): Remote[A] =
    self.drop(n).headOption.get

  def concat[B >: A](suffix: Remote[List[B]]): Remote[List[B]] =
    self :++ suffix

  def contains[A1 >: A](elem: Remote[A1]): Remote[Boolean] =
    Remote
      .recurse((Remote(false), self.widen[List[A1]])) { case (input, rec) =>
        Remote
          .UnCons(input._2)
          .fold((Remote(false), Remote.nil[A1]))((tuple: Remote[(A1, List[A1])]) =>
            (tuple._1 === elem).ifThenElse(
              ifTrue = (Remote(true), Remote.nil[A1]),
              ifFalse = rec((Remote(false), tuple._2))
            )
          )
      }
      ._1

  def containsSlice[B >: A](that: Remote[List[B]]): Remote[Boolean] =
    Remote
      .recurse((Remote(false), that.length, self.widen[List[B]])) { case (input, rec) =>
        val len     = input._2
        val current = input._3
        Remote
          .UnCons(current)
          .fold((Remote(false), len, Remote.nil[B]))((tuple: Remote[(B, List[B])]) =>
            (current.take(len) === that).ifThenElse(
              ifTrue = (Remote(true), len, Remote.nil[B]),
              ifFalse = rec((Remote(false), len, tuple._2))
            )
          )
      }
      ._1

  def corresponds[B](that: Remote[List[B]])(p: (Remote[A], Remote[B]) => Remote[Boolean]): Remote[Boolean] =
    (self.length === that.length) &&
      self.zip(that).forall((tuple: Remote[(A, B)]) => p(tuple._1, tuple._2))

  def drop(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue =
      Remote
        .UnCons(self)
        .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) => tuple._2.drop(num - Remote(1)))

    (num > Remote(0)).ifThenElse(ifTrue, self)
  }

  def dropWhile(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) => tuple._2.dropWhile(predicate))

  def filter(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    foldLeft[List[A]](Remote.nil[A])((a2: Remote[List[A]], a1: Remote[A]) =>
      predicate(a1).ifThenElse(Remote.Cons(a2, a1), a2)
    )

  def foldLeft[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    Remote.Fold(self, initial, UnboundRemoteFunction.make((tuple: Remote[(B, A)]) => f(tuple._1, tuple._2)))

  def headOption: Remote[Option[A]] = Remote
    .UnCons(self)
    .fold[Option[A]](Remote.none[A])(tuple => Remote.RemoteSome(tuple._1))

  def isEmpty: Remote[Boolean] =
    self.headOption.isNone.ifThenElse(Remote(true), Remote(false))

  def forall(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    foldLeft(Remote(true))((result: Remote[Boolean], elem: Remote[A]) => result && p(elem))

  def length: Remote[Int] =
    self.foldLeft[Int](0)((len, _) => len + 1)

  def product(implicit numeric: Numeric[A]): Remote[A] =
    foldLeft[A](numeric.fromLong(1L))(_ * _)

  def reverse: Remote[List[A]] =
    foldLeft(Remote.nil[A])((l, a) => Remote.Cons(l, a))

  def sum(implicit numeric: Numeric[A]): Remote[A] =
    foldLeft[A](numeric.fromLong(0L))(_ + _)

  def take(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue: Remote[List[A]] =
      Remote
        .UnCons(self.widen[List[A]])
        .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) => Remote.Cons(tuple._2.take(num - Remote(1)), tuple._1))
    (num > Remote(0)).ifThenElse(ifTrue, Remote.nil[A])
  }

  def takeWhile(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    Remote
      .UnCons(self)
      .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) =>
        predicate(tuple._1).ifThenElse(Remote.Cons(tuple._2.takeWhile(predicate), tuple._1), Remote.nil)
      )

  def zip[B](that: Remote[List[B]]): Remote[List[(A, B)]] =
    Remote
      .recurse((Remote.nil[(A, B)], self, that)) { case (input, rec) =>
        val result      = input._1
        val currentSelf = input._2
        val currentThat = input._3

        Remote
          .UnCons(currentSelf)
          .fold((result, Remote.nil[A], Remote.nil[B])) { tupleSelf: Remote[(A, List[A])] =>
            Remote
              .UnCons(currentThat)
              .fold((result, Remote.nil[A], Remote.nil[B])) { tupleThat: Remote[(B, List[B])] =>
                rec(((tupleSelf._1, tupleThat._1) :: result, tupleSelf._2, tupleThat._2))
              }
          }
      }
      ._1
}
