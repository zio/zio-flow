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

package zio

import zio.flow.remote._
import zio.prelude.Newtype
import zio.schema.Schema

import java.time.Instant
import scala.language.implicitConversions

package object flow extends Schemas {
  type ActivityError = Throwable

  type RemoteDuration    = Remote[Duration]
  type RemoteInstant     = Remote[Instant]
  type RemoteVariable[A] = Remote.Variable[A]
  type SchemaOption[A]   = Schema.Optional[A]
  type SchemaList[A]     = Schema[List[A]]

  implicit def RemoteVariable[A](remote: Remote[Remote.Variable[A]]): RemoteVariableSyntax[A] =
    new RemoteVariableSyntax(
      remote
    )
  implicit def RemoteInstant(remote: Remote[Instant]): RemoteInstantSyntax = new RemoteInstantSyntax(
    remote
  )
  implicit def RemoteDuration(remote: Remote[Duration]): RemoteDurationSyntax = new RemoteDurationSyntax(
    remote
  )
  implicit def RemoteBoolean(remote: Remote[Boolean]): RemoteBooleanSyntax = new RemoteBooleanSyntax(remote)

  implicit def RemoteEither[A, B](remote: Remote[Either[A, B]]): RemoteEitherSyntax[A, B] = new RemoteEitherSyntax(
    remote
  )
  implicit def RemoteTuple2[A, B](remote: Remote[(A, B)]): RemoteTuple2Syntax[A, B] = new RemoteTuple2Syntax(remote)

  implicit def RemoteTuple3[A, B, C](remote: Remote[(A, B, C)]): RemoteTuple3Syntax[A, B, C] = new RemoteTuple3Syntax(
    remote
  )
  implicit def RemoteTuple4[A, B, C, D](remote: Remote[(A, B, C, D)]): RemoteTuple4Syntax[A, B, C, D] =
    new RemoteTuple4Syntax(remote)

  implicit def RemoteTuple5[A, B, C, D, E](remote: Remote[(A, B, C, D, E)]): RemoteTuple5Syntax[A, B, C, D, E] =
    new RemoteTuple5Syntax(remote)

  implicit def RemoteList[A](remote: Remote[List[A]]): RemoteListSyntax[A] = new RemoteListSyntax[A](remote)

  implicit def RemoteOption[A](remote: Remote[Option[A]]): RemoteOptionSyntax[A] = new RemoteOptionSyntax[A](remote)

  implicit def RemoteString(remote: Remote[String]): RemoteStringSyntax = new RemoteStringSyntax(remote)

  implicit def RemoteExecutingFlow[A](remote: Remote[A]): RemoteExecutingFlowSyntax[A] =
    new RemoteExecutingFlowSyntax[A](remote)

  implicit def RemoteNumeric[A](remote: Remote[A]): RemoteNumericSyntax[A] = new RemoteNumericSyntax[A](remote)

  implicit def RemoteRelational[A](remote: Remote[A]): RemoteRelationalSyntax[A] = new RemoteRelationalSyntax[A](remote)

  implicit def RemoteFractional[A](remote: Remote[A]): RemoteFractionalSyntax[A] = new RemoteFractionalSyntax[A](remote)

  object RemoteVariableName extends Newtype[String]
  type RemoteVariableName = RemoteVariableName.Type
}
