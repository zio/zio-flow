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
import zio.schema.DeriveSchema.gen

final class RemoteOptionSyntax[A](val self: Remote[Option[A]]) extends AnyVal {

  def contains[A1 >: A](elem: Remote[A1]): Remote[Boolean] =
    fold(Remote(false))(value => value.widen[A1] == elem)

  def exists(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    fold(Remote(false))(value => p(value))

  def filter(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    fold(Remote.none[A])(value =>
      p(value).ifThenElse(
        ifTrue = self,
        ifFalse = Remote.none[A]
      )
    )

  def filterNot(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    fold(Remote.none[A])(value =>
      p(value).ifThenElse(
        ifTrue = Remote.none[A],
        ifFalse = self
      )
    )

  def flatMap[B](f: Remote[A] => Remote[Option[B]]): Remote[Option[B]] =
    fold(Remote.none[B])(value => f(value))

  def fold[B](forNone: Remote[B])(f: Remote[A] => Remote[B]): Remote[B] =
    Remote.FoldOption(self, forNone, UnboundRemoteFunction.make(f))

  def foldLeft[B](z: Remote[B])(f: Remote[(B, A)] => Remote[B]): Remote[B] =
    fold(z)(value => f((z, value)))

  def foldRight[B](z: Remote[B])(f: Remote[(A, B)] => Remote[B]): Remote[B] =
    fold(z)(value => f((value, z)))

  def forall(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    fold(Remote(false))(value => p(value))

  def get: Remote[A] =
    fold(Remote.fail("get called on empty Option"))(x => x)

  def getOrElse[B >: A](default: Remote[B]): Remote[B] =
    fold(default)(x => x.widen)

  def head: Remote[A] = get

  def headOption: Remote[Option[A]] = self

  def isSome: Remote[Boolean] =
    fold(Remote(false))(_ => Remote(true))

  def isNone: Remote[Boolean] =
    fold(Remote(true))(_ => Remote(false))

  def isDefined: Remote[Boolean] = !isEmpty

  def isEmpty: Remote[Boolean] = isNone

  def knownSize: Remote[Int] = fold(Remote(0))(_ => Remote(1))

  def last: Remote[A] = get

  def lastOption: Remote[Option[A]] = self

  def map[B](f: Remote[A] => Remote[B]): Remote[Option[B]] =
    fold(Remote.none[B])(value => Remote.some(f(value)))

  def nonEmpty: Remote[Boolean] = isSome

  def orElse[B >: A](alternative: Remote[Option[B]]): Remote[Option[B]] =
    fold(alternative)(_ => self)

  def toLeft[R](right: Remote[R]): Remote[Either[A, R]] =
    fold(Remote.RemoteEither[A, R](Right(right)))(value => Remote.RemoteEither[A, R](Left(value)))

  def toList: Remote[List[A]] =
    fold(Remote.nil[A])(value => Remote.Cons(Remote.nil[A], value))

  def toRight[L](left: Remote[L]): Remote[Either[L, A]] =
    fold(Remote.RemoteEither[L, A](Left(left)))(value => Remote.RemoteEither[L, A](Right(value)))

  def zip[B](
    that: Remote[Option[B]]
  ): Remote[Option[(A, B)]] =
    self.fold(Remote.none[(A, B)])((a: Remote[A]) =>
      that.fold(Remote.none[(A, B)])((b: Remote[B]) => Remote.some(Remote.tuple2((a, b))))
    )
}
