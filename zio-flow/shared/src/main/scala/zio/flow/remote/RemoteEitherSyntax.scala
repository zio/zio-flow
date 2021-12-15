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

package zio.flow.remote

import scala.util.Try

import zio.flow.{Remote, ZFlow}
import zio.schema.Schema

class RemoteEitherSyntax[A, B](val self: Remote[Either[A, B]]) {

  final def handleEither[C](left: Remote[A] => Remote[C], right: Remote[B] => Remote[C]): Remote[C] =
    Remote.FoldEither(self, left, right)

  final def handleEitherM[R, E, C](
    left: Remote[A] => ZFlow[R, E, C],
    right: Remote[B] => ZFlow[R, E, C]
  ): ZFlow[R, E, C] = ZFlow.unwrap(handleEither(left.andThen(Remote(_)), right.andThen(Remote(_))))

  final def flatMap[A1 >: A, B1](f: Remote[B] => Remote[Either[A1, B1]])(implicit
    b1Schema: Schema[B1]
  ): Remote[Either[A1, B1]] =
    Remote.FlatMapEither(self, f, b1Schema)

  final def map[B1](
    f: Remote[B] => Remote[B1]
  )(implicit aSchema: Schema[A], b1Schema: Schema[B1]): Remote[Either[A, B1]] =
    Remote.FlatMapEither(self, (b: Remote[B]) => Remote.Either0(Right((aSchema, f(b)))), b1Schema)

  final def flatten[A1 >: A, B1](implicit ev: B <:< Either[A1, B1], b1Schema: Schema[B1]): Remote[Either[A1, B1]] =
    flatMap(_.asInstanceOf[Remote[Either[A1, B1]]])

  final def merge(implicit ev: Either[A, B] <:< Either[B, B]): Remote[B] =
    Remote.FoldEither[B, B, B](self.widen[Either[B, B]], identity(_), identity(_))

  final def isLeft: Remote[Boolean] =
    handleEither(_ => Remote(true), _ => Remote(false))

  final def isRight: Remote[Boolean] =
    handleEither(_ => Remote(false), _ => Remote(true))

  final def swap: Remote[Either[B, A]] = Remote.SwapEither(self)

  final def joinRight[A1 >: A, B1 >: B, C](implicit ev: B1 <:< Either[A1, C]): Remote[Either[A1, C]] =
    handleEither(
      _ => self.asInstanceOf[Remote[Either[A1, C]]],
      r => r.asInstanceOf[Remote[Either[A1, C]]]
    )

  final def joinLeft[A1 >: A, B1 >: B, C](implicit ev: A1 <:< Either[C, B1]): Remote[Either[C, B1]] =
    handleEither(
      l => l.asInstanceOf[Remote[Either[C, B1]]],
      _ => self.asInstanceOf[Remote[Either[C, B1]]]
    )

  final def contains[B1 >: B](elem: Remote[B1]): Remote[Boolean] =
    handleEither(_ => Remote(false), Remote.Equal(_, elem))

  final def forall(f: Remote[B] => Remote[Boolean]): Remote[Boolean] = handleEither(_ => Remote(true), f)

  final def exists(f: Remote[B] => Remote[Boolean]): Remote[Boolean] = handleEither(_ => Remote(false), f)

  final def getOrElse(or: => Remote[B]): Remote[B] = handleEither(_ => or, identity(_))

  final def orElse[A1 >: A, B1 >: B](or: => Remote[Either[A1, B1]]): Remote[Either[A1, B1]] =
    handleEither(_ => or, _ => self)

  final def filterOrElse[A1 >: A](p: Remote[B] => Remote[Boolean], zero: => Remote[A1])(implicit
    bSchema: Schema[B] // FIXME Actually easily retrieved from Remote[B]
  ): Remote[Either[A1, B]] =
    handleEither(
      _ => self,
      a =>
        Remote.Branch(
          p(a),
          self,
          Remote.Either0(Left((zero, bSchema)))
        )
    )

  final def toSeq: Remote[Seq[B]] = handleEither(_ => Remote(Nil), b => Remote.Cons(Remote(Nil), b))

  final def toOption: Remote[Option[B]] = handleEither(_ => Remote(None), Remote.Some0(_))

  def toTry(implicit ev: A <:< Throwable, schema: Schema[B]): Remote[Try[B]] =
    handleEither(a => Remote.Try(Left(a.widen(ev) -> schema)), b => Remote.Try(Right(b)))
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
