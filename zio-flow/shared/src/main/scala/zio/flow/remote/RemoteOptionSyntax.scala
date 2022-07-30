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

  def fold[B](forNone: Remote[B], f: Remote[A] => Remote[B]): Remote[B] =
    Remote.FoldOption(self, forNone, UnboundRemoteFunction.make(f))

  def isSome: Remote[Boolean] =
    fold(Remote(false), _ => Remote(true))

  def isNone: Remote[Boolean] =
    fold(Remote(true), _ => Remote(false))

  def isEmpty: Remote[Boolean] = isNone

  def isDefined: Remote[Boolean] = !isEmpty

  def knownSize: Remote[Int] = fold(Remote(0), _ => Remote(1))

  def contains[A1 >: A](elem: Remote[A1]): Remote[Boolean] =
    self.fold(
      Remote(false),
      (value: Remote[A]) => value.widen[A1] === elem
    )

  def orElse[B >: A](alternative: Remote[Option[B]]): Remote[Option[B]] = fold(alternative, _ => self)

  def zip[B](
    that: Remote[Option[B]]
  ): Remote[Option[(A, B)]] =
    self.fold(
      Remote.none,
      (a: Remote[A]) =>
        that.fold(
          Remote.none,
          (b: Remote[B]) => Remote.RemoteSome(Remote.tuple2((a, b)))
        )
    )
}
