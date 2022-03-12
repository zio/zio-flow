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
import zio.flow.{Remote, SchemaOrNothing, ZFlow}
import zio.schema.Schema

import scala.util.Try

class RemoteEitherSyntax[A, B](val self: Remote[Either[A, B]]) {

  final def handleEither[C](left: Remote[A] => Remote[C], right: Remote[B] => Remote[C])(implicit
    schemaA: SchemaOrNothing.Aux[A],
    schemaB: SchemaOrNothing.Aux[B]
  ): Remote[C] =
    Remote.FoldEither[A, B, C](self, RemoteFunction(left).evaluated, RemoteFunction(right).evaluated)

  final def handleEitherM[R, E: Schema, C: Schema](
    left: Remote[A] => ZFlow[R, E, C],
    right: Remote[B] => ZFlow[R, E, C]
  )(implicit
    schemaA: SchemaOrNothing.Aux[A],
    schemaB: SchemaOrNothing.Aux[B]
  ): ZFlow[R, E, C] = ZFlow.unwrap(handleEither(left.andThen(Remote(_)), right.andThen(Remote(_))))

  final def flatMap[A1 >: A, B1](f: Remote[B] => Remote[Either[A1, B1]])(implicit
    schemaA: SchemaOrNothing.Aux[A1],
    schemaB: SchemaOrNothing.Aux[B],
    schemaB1: SchemaOrNothing.Aux[B1]
  ): Remote[Either[A1, B1]] =
    Remote.FlatMapEither(self, f.evaluated, schemaA, schemaB1)

  final def map[B1](
    f: Remote[B] => Remote[B1]
  )(implicit
    schemaA: SchemaOrNothing.Aux[A],
    schemaB: SchemaOrNothing.Aux[B],
    schemaB1: SchemaOrNothing.Aux[B1]
  ): Remote[Either[A, B1]] =
    Remote.FlatMapEither(self, ((b: Remote[B]) => Remote.Either0(Right((schemaA, f(b))))).evaluated, schemaA, schemaB1)

  final def flatten[A1 >: A, B1](implicit
    ev: B <:< Either[A1, B1],
    schemaA1: SchemaOrNothing.Aux[A1],
    schemaB: SchemaOrNothing.Aux[B],
    schemaB1: SchemaOrNothing.Aux[B1]
  ): Remote[Either[A1, B1]] =
    flatMap(_.asInstanceOf[Remote[Either[A1, B1]]])

  final def merge(implicit ev: Either[A, B] <:< Either[B, B], schemaB: SchemaOrNothing.Aux[B]): Remote[B] =
    Remote.FoldEither[B, B, B](
      self.widen[Either[B, B]],
      RemoteFunction(identity[Remote[B]]).evaluated,
      RemoteFunction(identity[Remote[B]]).evaluated
    )

  final def isLeft(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Boolean] =
    handleEither(_ => Remote(true), _ => Remote(false))

  final def isRight(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Boolean] =
    handleEither(_ => Remote(false), _ => Remote(true))

  final def swap: Remote[Either[B, A]] = Remote.SwapEither(self)

  final def joinRight[A1 >: A, B1 >: B, C](implicit
    ev: B1 <:< Either[A1, C],
    schemaA: SchemaOrNothing.Aux[A],
    schemaB: SchemaOrNothing.Aux[B]
  ): Remote[Either[A1, C]] =
    handleEither(
      _ => self.asInstanceOf[Remote[Either[A1, C]]],
      r => r.asInstanceOf[Remote[Either[A1, C]]]
    )

  final def joinLeft[A1 >: A, B1 >: B, C](implicit
    ev: A1 <:< Either[C, B1],
    schemaA: SchemaOrNothing.Aux[A],
    schemaB: SchemaOrNothing.Aux[B]
  ): Remote[Either[C, B1]] =
    handleEither(
      l => l.asInstanceOf[Remote[Either[C, B1]]],
      _ => self.asInstanceOf[Remote[Either[C, B1]]]
    )

  final def contains[B1 >: B](
    elem: Remote[B1]
  )(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Boolean] =
    handleEither(_ => Remote(false), Remote.Equal(_, elem))

  final def forall(
    f: Remote[B] => Remote[Boolean]
  )(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Boolean] =
    handleEither(_ => Remote(true), f)

  final def exists(
    f: Remote[B] => Remote[Boolean]
  )(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Boolean] =
    handleEither(_ => Remote(false), f)

  final def getOrElse(
    or: => Remote[B]
  )(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[B] =
    handleEither(_ => or, identity)

  final def orElse[A1 >: A, B1 >: B](
    or: => Remote[Either[A1, B1]]
  )(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Either[A1, B1]] =
    handleEither(_ => or, _ => self)

  final def filterOrElse[A1 >: A](p: Remote[B] => Remote[Boolean], zero: => Remote[A1])(implicit
    schemaA: SchemaOrNothing.Aux[A],
    schemaB: SchemaOrNothing.Aux[B]
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

  final def toSeq(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Seq[B]] =
    handleEither(_ => Remote(Nil), b => Remote.Cons(Remote(Nil), b))

  final def toOption(implicit schemaA: SchemaOrNothing.Aux[A], schemaB: SchemaOrNothing.Aux[B]): Remote[Option[B]] =
    handleEither(_ => Remote(None), Remote.Some0(_))

  def toTry(implicit
    ev: A <:< Throwable,
    schemaA: SchemaOrNothing.Aux[A],
    schemaB: SchemaOrNothing.Aux[B]
  ): Remote[Try[B]] =
    handleEither(a => Remote.Try(Left(a.widen(ev) -> schemaB)), b => Remote.Try(Right(b)))
}

object RemoteEitherSyntax {

  def collectAll[E, A](
    values: Remote[List[Either[E, A]]]
  )(implicit eSchema: SchemaOrNothing.Aux[E], aSchema: SchemaOrNothing.Aux[A]): Remote[Either[E, List[A]]] = {

    def combine(
      eitherList: RemoteEitherSyntax[E, List[A]],
      either: RemoteEitherSyntax[E, A]
    ): Remote[Either[E, List[A]]] = {
      implicit val listSchema = SchemaOrNothing.fromSchema(Schema.list(aSchema.schema)) // TODO: :((
      eitherList.handleEither(
        _ => eitherList.self,
        remoteList => combine2(either, remoteList).self
      )
    }

    def combine2(either: RemoteEitherSyntax[E, A], remoteList: Remote[List[A]]): RemoteEitherSyntax[E, List[A]] =
      either.handleEither(
        e => Remote.Either0(Left((e, SchemaOrNothing.fromSchema(Schema.list(aSchema.schema))))),
        a => Remote.Either0(Right((eSchema, Remote.Cons(remoteList, a))))
      )

    def finalize(acc: RemoteEitherSyntax[E, List[A]]): Remote[Either[E, List[A]]] = {
      implicit val sa: Schema[A] = aSchema.schema

      acc.handleEither(
        _ => acc.self,
        as => Remote.Either0(Right((eSchema, as.reverse)))
      )
    }

    implicit val eea: Schema[Either[E, A]] =
      Schema.either(eSchema.schema, aSchema.schema) // TODO: :((
    implicit val eela: Schema[Either[E, List[A]]] =
      Schema.either(eSchema.schema, Schema.list(aSchema.schema)) // TODO: :((

    finalize(values.fold(Remote(Right(Nil): Either[E, List[A]]))(combine(_, _)))
  }
}
