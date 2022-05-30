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

import zio.flow.Remote.RemoteFunction
import zio.flow._
import zio.schema.DeriveSchema.gen
import zio.schema.Schema

final class RemoteOptionSyntax[A](val self: Remote[Option[A]]) extends AnyVal {

  def fold[B](forNone: Remote[B], f: Remote[A] => Remote[B])(implicit
    schemaA: Schema[A]
  ): Remote[B] =
    Remote.FoldOption(self, forNone, RemoteFunction(f).evaluated)

  def isSome(implicit
    schemaA: Schema[A]
  ): Remote[Boolean] =
    fold(Remote(false), _ => Remote(true))

  def isNone(implicit
    schemaA: Schema[A]
  ): Remote[Boolean] =
    fold(Remote(true), _ => Remote(false))

  def isEmpty(implicit
    schemaA: Schema[A]
  ): Remote[Boolean] = isNone

  def isDefined(implicit
    schemaA: Schema[A]
  ): Remote[Boolean] = !isEmpty

  def knownSize(implicit
    schemaA: Schema[A]
  ): Remote[Int] = fold(Remote(0), _ => Remote(1))

  def contains[A1 >: A](elem: Remote[A1])(implicit schemaA: Schema[A]): Remote[Boolean] =
    self.fold(
      Remote(false),
      (value: Remote[A]) => value.widen[A1] === elem
    )

  def orElse[B >: A](alternative: Remote[Option[B]])(implicit
    schemaA: Schema[A]
  ): Remote[Option[B]] = fold(alternative, _ => self)

  def zip[B](
    that: Remote[Option[B]]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Option[(A, B)]] =
    self.fold(
      Remote[Option[(A, B)]](None),
      (a: Remote[A]) =>
        that.fold(
          Remote[Option[(A, B)]](None),
          (b: Remote[B]) => Remote.RemoteSome(Remote.tuple2((a, b)))
        )
    )
}
