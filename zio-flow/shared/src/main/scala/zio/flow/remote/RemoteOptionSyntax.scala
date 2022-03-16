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
//
//package zio.flow.remote
//
//import zio.flow.Remote.RemoteFunction
//import zio.flow._
//import zio.schema.DeriveSchema.gen
//import zio.schema.Schema
//
//class RemoteOptionSyntax[A](val self: Remote[Option[A]]) {
//
//  def handleOption[B](forNone: Remote[B], f: Remote[A] => Remote[B])(implicit
//    schemaA: Schema[A]
//  ): Remote[B] =
//    Remote.FoldOption(self, forNone, RemoteFunction(f))
//
//  def isSome(implicit
//    schemaA: Schema[A]
//  ): Remote[Boolean] =
//    handleOption(Remote(false), _ => Remote(true))
//
//  def isNone(implicit
//    schemaA: Schema[A]
//  ): Remote[Boolean] =
//    handleOption(Remote(true), _ => Remote(false))
//
//  final def isEmpty(implicit
//    schemaA: Schema[A]
//  ): Remote[Boolean] = isNone
//
//  final def isDefined(implicit
//    schemaA: Schema[A]
//  ): Remote[Boolean] = !isEmpty
//
//  final def knownSize(implicit
//    schemaA: Schema[A]
//  ): Remote[Int] = handleOption(Remote(0), _ => Remote(1))
//
//  final def contains[A1 >: A](elem: A1): Remote[Boolean] = Remote.ContainsOption(self, elem)
//
//  def orElse[B >: A](alternative: Remote[Option[B]])(implicit
//    schemaA: Schema[A]
//  ): Remote[Option[B]] = handleOption(alternative, _ => self)
//
//  final def zip[A1 >: A, B](that: Remote[Option[B]]): Remote[Option[(A1, B)]] = Remote.ZipOption(self, that)
//}
