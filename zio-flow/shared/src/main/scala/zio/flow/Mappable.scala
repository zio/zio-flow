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

package zio.flow

import zio.flow.Remote.EvaluatedRemoteFunction
import zio.schema.Schema

sealed trait Mappable[F[_]] {
  def performMap[A: Schema, B: Schema](fa: Remote[F[A]], ab: Remote[A] => Remote[B]): Remote[F[B]]

  def performFilter[A: Schema](fa: Remote[F[A]], predicate: Remote[A] => Remote[Boolean]): Remote[F[A]]

  def performFlatmap[A: Schema, B: Schema](fa: Remote[F[A]], ab: Remote[A] => Remote[F[B]]): Remote[F[B]]
}

object Mappable {

  implicit case object MappableOption extends Mappable[Option] {
    override def performMap[A: Schema, B: Schema](
      fa: Remote[Option[A]],
      ab: Remote[A] => Remote[B]
    ): Remote[Option[B]] =
      Remote.FoldOption(
        fa,
        Remote[Option[B]](None),
        EvaluatedRemoteFunction.make((a: Remote[A]) => Remote.RemoteSome(ab(a)))
      )

    override def performFilter[A: Schema](
      fa: Remote[Option[A]],
      predicate: Remote[A] => Remote[Boolean]
    ): Remote[Option[A]] =
      Remote.FoldOption(
        fa,
        Remote[Option[A]](None),
        EvaluatedRemoteFunction.make((a: Remote[A]) => predicate(a).ifThenElse(fa, Remote.none[A]))
      )

    override def performFlatmap[A: Schema, B: Schema](
      fa: Remote[Option[A]],
      ab: Remote[A] => Remote[Option[B]]
    ): Remote[Option[B]] =
      Remote.FoldOption(fa, Remote.none[B], EvaluatedRemoteFunction.make(ab))
  }

  implicit case object MappableList extends Mappable[List] {

    override def performMap[A: Schema, B: Schema](fa: Remote[List[A]], ab: Remote[A] => Remote[B]): Remote[List[B]] =
      Remote.Fold(
        fa,
        Remote(List.empty[B]),
        EvaluatedRemoteFunction.make((tuple: Remote[(List[B], A)]) => Remote.Cons(tuple._1, ab(tuple._2)))
      )

    override def performFilter[A: Schema](
      fa: Remote[List[A]],
      predicate: Remote[A] => Remote[Boolean]
    ): Remote[List[A]] =
      Remote.Fold(
        fa,
        Remote(List.empty[A]),
        EvaluatedRemoteFunction.make((tuple: Remote[(List[A], A)]) =>
          predicate(tuple._2).ifThenElse(Remote.Cons(tuple._1, tuple._2), tuple._1)
        )
      )

    override def performFlatmap[A: Schema, B: Schema](
      fa: Remote[List[A]],
      ab: Remote[A] => Remote[List[B]]
    ): Remote[List[B]] =
      Remote.Fold(
        fa,
        Remote(List.empty[B]),
        EvaluatedRemoteFunction.make((tuple: Remote[(List[B], A)]) => tuple._1 ++ ab(tuple._2))
      )
  }

}
