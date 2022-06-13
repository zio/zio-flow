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
import zio.flow.{Remote, ZFlow}
import zio.schema.Schema

import scala.util.Try

final class RemoteEitherSyntax[A, B](val self: Remote[Either[A, B]]) extends AnyVal {

  def fold[C](left: Remote[A] => Remote[C], right: Remote[B] => Remote[C])(implicit
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[C] =
    Remote.FoldEither[A, B, C](self, RemoteFunction(left).evaluated, RemoteFunction(right).evaluated)

  def handleEitherFlow[R, E: Schema, C: Schema](
    left: Remote[A] => ZFlow[R, E, C],
    right: Remote[B] => ZFlow[R, E, C]
  )(implicit
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): ZFlow[R, E, C] = ZFlow.unwrap(fold(left.andThen(Remote(_)), right.andThen(Remote(_))))

  def flatMap[A1 >: A, B1](f: Remote[B] => Remote[Either[A1, B1]])(implicit
    schemaA: Schema[A1],
    schemaB: Schema[B],
    schemaB1: Schema[B1]
  ): Remote[Either[A1, B1]] =
    Remote.FoldEither[A1, B, Either[A1, B1]](
      self,
      RemoteFunction((a: Remote[A1]) => Remote.RemoteEither(Left((a, schemaB1)))).evaluated,
      f.evaluated
    )

  def map[B1](
    f: Remote[B] => Remote[B1]
  )(implicit
    schemaA: Schema[A],
    schemaB: Schema[B],
    schemaB1: Schema[B1]
  ): Remote[Either[A, B1]] =
    Remote.FoldEither[A, B, Either[A, B1]](
      self,
      RemoteFunction((a: Remote[A]) => Remote.RemoteEither(Left((a, schemaB1)))).evaluated,
      RemoteFunction((b: Remote[B]) => Remote.RemoteEither(Right((schemaA, f(b))))).evaluated
    )

  def flatten[A1 >: A, B1](implicit
    ev: B <:< Either[A1, B1],
    schemaA1: Schema[A1],
    schemaB: Schema[B],
    schemaB1: Schema[B1]
  ): Remote[Either[A1, B1]] =
    flatMap(_.asInstanceOf[Remote[Either[A1, B1]]])

  def merge(implicit ev: Either[A, B] <:< Either[B, B], schemaB: Schema[B]): Remote[B] =
    Remote.FoldEither[B, B, B](
      self.widen[Either[B, B]],
      RemoteFunction(identity[Remote[B]]).evaluated,
      RemoteFunction(identity[Remote[B]]).evaluated
    )

  def isLeft(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    fold(_ => Remote(true), _ => Remote(false))

  def isRight(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    fold(_ => Remote(false), _ => Remote(true))

  def swap: Remote[Either[B, A]] = Remote.SwapEither(self)

  def joinRight[A1 >: A, B1 >: B, C](implicit
    ev: B1 <:< Either[A1, C],
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Either[A1, C]] =
    fold(
      _ => self.asInstanceOf[Remote[Either[A1, C]]],
      r => r.asInstanceOf[Remote[Either[A1, C]]]
    )

  def joinLeft[A1 >: A, B1 >: B, C](implicit
    ev: A1 <:< Either[C, B1],
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Either[C, B1]] =
    fold(
      l => l.asInstanceOf[Remote[Either[C, B1]]],
      _ => self.asInstanceOf[Remote[Either[C, B1]]]
    )

  def contains[B1 >: B](
    elem: Remote[B1]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    fold(_ => Remote(false), Remote.Equal(_, elem))

  def forall(
    f: Remote[B] => Remote[Boolean]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    fold(_ => Remote(true), f)

  def exists(
    f: Remote[B] => Remote[Boolean]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Boolean] =
    fold(_ => Remote(false), f)

  def getOrElse(
    or: => Remote[B]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[B] =
    fold(_ => or, identity)

  def orElse[A1 >: A, B1 >: B](
    or: => Remote[Either[A1, B1]]
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Either[A1, B1]] =
    fold(_ => or, _ => self)

  def filterOrElse[A1 >: A](p: Remote[B] => Remote[Boolean], zero: => Remote[A1])(implicit
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Either[A1, B]] =
    fold(
      _ => self,
      a =>
        Remote.Branch(
          p(a),
          self,
          Remote.RemoteEither(Left((zero, schemaB)))
        )
    )

  def toSeq(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Seq[B]] =
    fold(_ => Remote[List[B]](Nil), b => Remote.Cons(Remote[List[B]](Nil), b))

  def toOption(implicit schemaA: Schema[A], schemaB: Schema[B]): Remote[Option[B]] =
    fold(_ => Remote[Option[B]](None)(Schema.option(schemaB)), Remote.RemoteSome(_))

  def toTry(implicit
    ev: A <:< Throwable,
    schemaA: Schema[A],
    schemaB: Schema[B]
  ): Remote[Try[B]] =
    fold(a => Remote.Try(Left(a.widen(ev) -> schemaB)), b => Remote.Try(Right(b)))
}

object RemoteEitherSyntax {

  def collectAll[E, A](
    values: Remote[List[Either[E, A]]]
  )(implicit eSchema: Schema[E], aSchema: Schema[A]): Remote[Either[E, List[A]]] = {

    def combine(
      eitherList: RemoteEitherSyntax[E, List[A]],
      either: RemoteEitherSyntax[E, A]
    ): Remote[Either[E, List[A]]] = {
      implicit val listSchema: Schema[List[A]] = Schema.list(aSchema) // TODO: :((
      eitherList.fold(
        _ => eitherList.self,
        remoteList => combine2(either, remoteList).self
      )
    }

    def combine2(either: RemoteEitherSyntax[E, A], remoteList: Remote[List[A]]): RemoteEitherSyntax[E, List[A]] =
      either.fold(
        e => Remote.RemoteEither(Left((e, Schema.list(aSchema)))),
        a => Remote.RemoteEither(Right((eSchema, Remote.Cons(remoteList, a))))
      )

    def finalize(acc: RemoteEitherSyntax[E, List[A]]): Remote[Either[E, List[A]]] =
      acc.fold(
        _ => acc.self,
        as => Remote.RemoteEither(Right((eSchema, as.reverse)))
      )

    val nil = Remote[Either[E, List[A]]](Right(Nil))
    finalize(values.fold(nil)((b: Remote[Either[E, List[A]]], a: Remote[Either[E, A]]) => combine(b, a)))
  }
}
