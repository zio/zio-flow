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
import zio.flow.{Remote, ZFlow}
import zio.schema.Schema

import scala.util.Try

class RemoteEitherSyntax[A, B](val self: Remote[Either[A, B]]) {

  final def handleEither[C](left: Remote[A] => Remote[C], right: Remote[B] => Remote[C])(implicit
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[C] =
    Remote.FoldEither[A, B, C](self, RemoteFunction(left), RemoteFunction(right))

  final def handleEitherM[R, E: Schema, C: Schema](
    left: Remote[A] => ZFlow[R, E, C],
    right: Remote[B] => ZFlow[R, E, C]
  )(implicit
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): ZFlow[R, E, C] = ZFlow.unwrap(handleEither(left.andThen(Remote(_)), right.andThen(Remote(_))))

  final def flatMap[A1 >: A, B1](f: Remote[B] => Remote[Either[A1, B1]])(implicit
    schemaA: Schema[A1],
    schemaB: Schema[B],
    schemaB1: Schema[B1]
  ): Remote[Either[A1, B1]] =
    Remote.FlatMapEither(self, f, schemaA, schemaB1)

  final def map[B1](
    f: Remote[B] => Remote[B1]
  )(implicit
    schemaA: Schema[A],
    schemaB: Schema[B],
    schemaB1: Schema[B1]
  ): Remote[Either[A, B1]] =
    Remote.FlatMapEither(self, (b: Remote[B]) => Remote.Either0(Right((schemaA, f(b)))), schemaA, schemaB1)

  final def flatten[A1 >: A, B1](implicit
    ev: B <:< Either[A1, B1],
    schemaA1: Schema[A1],
    schemaB: Schema[B],
    schemaB1: Schema[B1]
  ): Remote[Either[A1, B1]] =
    flatMap(_.asInstanceOf[Remote[Either[A1, B1]]])

  final def merge(implicit ev: Either[A, B] <:< Either[B, B], schemaB: Schema[B]): Remote[B] =
    Remote.FoldEither[B, B, B](self.widen[Either[B, B]], RemoteFunction(identity(_)), RemoteFunction(identity(_)))

  final def isLeft(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    handleEither(_ => Remote(true), _ => Remote(false))

  final def isRight(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    handleEither(_ => Remote(false), _ => Remote(true))

  final def swap: Remote[Either[B, A]] = Remote.SwapEither(self)

  final def joinRight[A1 >: A, B1 >: B, C](implicit
    ev: B1 <:< Either[A1, C],
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Either[A1, C]] =
    handleEither(
      _ => self.asInstanceOf[Remote[Either[A1, C]]],
      r => r.asInstanceOf[Remote[Either[A1, C]]]
    )

  final def joinLeft[A1 >: A, B1 >: B, C](implicit
    ev: A1 <:< Either[C, B1],
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Either[C, B1]] =
    handleEither(
      l => l.asInstanceOf[Remote[Either[C, B1]]],
      _ => self.asInstanceOf[Remote[Either[C, B1]]]
    )

  final def contains[B1 >: B](
    elem: Remote[B1]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    handleEither(_ => Remote(false), Remote.Equal(_, elem))

  final def forall(
    f: Remote[B] => Remote[Boolean]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    handleEither(_ => Remote(true), f)

  final def exists(
    f: Remote[B] => Remote[Boolean]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    handleEither(_ => Remote(false), f)

  final def getOrElse(
    or: => Remote[B]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[B] =
    handleEither(_ => or, identity)

  final def orElse[A1 >: A, B1 >: B](
    or: => Remote[Either[A1, B1]]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Either[A1, B1]] =
    handleEither(_ => or, _ => self)

  final def filterOrElse[A1 >: A](p: Remote[B] => Remote[Boolean], zero: => Remote[A1])(implicit
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Either[A1, B]] =
    handleEither(
      _ => self,
      a =>
        Remote.Branch(
          p(a),
          self,
          Remote.Either0(Left((zero, schemaB)))
        )
    )

  final def toSeq(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Seq[B]] =
    handleEither(_ => Remote(Nil), b => Remote.Cons(Remote(Nil), b))

  final def toOption(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Option[B]] =
    handleEither(_ => Remote(None), Remote.Some0(_))

  def toTry(implicit
    ev: A <:< Throwable,
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Try[B]] =
    handleEither(a => Remote.Try(Left(a.widen(ev) -> schemaB)), b => Remote.Try(Right(b)))
}

object RemoteEitherSyntax {

  def collectAll[E, A](
    values: Remote[List[Either[E, A]]]
  )(implicit eSchema: Schema[E], aSchema: Schema[A]): Remote[Either[E, List[A]]] = {

    def combine(
      eitherList: RemoteEitherSyntax[E, List[A]],
      either: RemoteEitherSyntax[E, A]
    ): Remote[Either[E, List[A]]] =
      eitherList.handleEither(
        _ => eitherList.self,
        remoteList => combine2(either, remoteList).self
      )

    def combine2(either: RemoteEitherSyntax[E, A], remoteList: Remote[List[A]]): RemoteEitherSyntax[E, List[A]] =
      either.handleEither(
        e => Remote.Either0(Left((e, Schema.list(aSchema)))),
        a => Remote.Either0(Right((eSchema, Remote.Cons(remoteList, a))))
      )

    def finalize(acc: RemoteEitherSyntax[E, List[A]]): Remote[Either[E, List[A]]] =
      acc.handleEither(
        _ => acc.self,
        as => Remote.Either0(Right((eSchema, as.reverse)))
      )

    finalize(values.fold(Remote(Right(Nil): Either[E, List[A]]))(combine(_, _)))
  }
}
