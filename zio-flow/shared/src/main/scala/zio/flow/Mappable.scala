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

package zio.flow

import zio.flow.remote.Remote

sealed trait Mappable[F[_]] {
  def performMap[A, B](fa: Remote[F[A]], ab: Remote[A] => Remote[B]): Remote[F[B]]

  def performFilter[A](fa: Remote[F[A]], predicate: Remote[A] => Remote[Boolean]): Remote[F[A]]

  def performFlatmap[A, B](fa: Remote[F[A]], ab: Remote[A] => Remote[F[B]]): Remote[F[B]]
}

object Mappable {

  implicit case object MappableOption extends Mappable[Option] {
    override def performMap[A, B](fa: Remote[Option[A]], ab: Remote[A] => Remote[B]): Remote[Option[B]] =
      Remote.FoldOption(fa, Remote(None), (a: Remote[A]) => Remote.Some0(ab(a)))

    override def performFilter[A](fa: Remote[Option[A]], predicate: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
      Remote.FoldOption(fa, Remote(None), (a: Remote[A]) => predicate(a).ifThenElse(fa, Remote(None)))

    override def performFlatmap[A, B](fa: Remote[Option[A]], ab: Remote[A] => Remote[Option[B]]): Remote[Option[B]] =
      Remote.FoldOption(fa, Remote(None), ab)
  }

  implicit case object MappableList extends Mappable[List] {

    override def performMap[A, B](fa: Remote[List[A]], ab: Remote[A] => Remote[B]): Remote[List[B]] =
      Remote.Fold(fa, Remote(Nil), (tuple: Remote[(List[B], A)]) => Remote.Cons(tuple._1, ab(tuple._2)))

    override def performFilter[A](fa: Remote[List[A]], predicate: Remote[A] => Remote[Boolean]): Remote[List[A]] =
      Remote.Fold(
        fa,
        Remote(Nil),
        (tuple: Remote[(List[A], A)]) => predicate(tuple._2).ifThenElse(Remote.Cons(tuple._1, tuple._2), tuple._1)
      )

    override def performFlatmap[A, B](fa: Remote[List[A]], ab: Remote[A] => Remote[List[B]]): Remote[List[B]] =
      Remote.Fold(fa, Remote(Nil), (tuple: Remote[(List[B], A)]) => tuple._1 ++ ab(tuple._2))
  }

}
