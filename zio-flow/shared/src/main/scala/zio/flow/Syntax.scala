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

package zio.flow

import zio.Duration
import zio.flow.remote._
import zio.flow.remote.RemoteTuples._
import java.time.Instant
import scala.language.implicitConversions

trait Syntax {

  implicit def RemoteVariable[A](remote: Remote[RemoteVariableReference[A]]): RemoteVariableReferenceSyntax[A] =
    new RemoteVariableReferenceSyntax(
      remote
    )

  implicit def RemoteInstant(remote: Remote[Instant]): RemoteInstantSyntax = new RemoteInstantSyntax(
    remote
  )
  implicit def RemoteDuration(remote: Remote[Duration]): RemoteDurationSyntax = new RemoteDurationSyntax(
    remote
  )
  implicit def RemoteDurationCompanion(duration: Duration.type): RemoteDurationCompanionSyntax =
    new RemoteDurationCompanionSyntax(duration)
  implicit def RemoteBoolean(remote: Remote[Boolean]): RemoteBooleanSyntax = new RemoteBooleanSyntax(remote)

  implicit def RemoteEither[A, B](remote: Remote[Either[A, B]]): RemoteEitherSyntax[A, B] = new RemoteEitherSyntax(
    remote
  )

  implicit def remoteTuple2Syntax[T1, T2](remote: Remote[Tuple2[T1, T2]]): RemoteTuple2.Syntax[T1, T2] =
    new RemoteTuple2.Syntax(remote)
  implicit def remoteTuple3Syntax[T1, T2, T3](remote: Remote[Tuple3[T1, T2, T3]]): RemoteTuple3.Syntax[T1, T2, T3] =
    new RemoteTuple3.Syntax(remote)
  implicit def remoteTuple4Syntax[T1, T2, T3, T4](
    remote: Remote[Tuple4[T1, T2, T3, T4]]
  ): RemoteTuple4.Syntax[T1, T2, T3, T4] = new RemoteTuple4.Syntax(remote)
  implicit def remoteTuple5Syntax[T1, T2, T3, T4, T5](
    remote: Remote[Tuple5[T1, T2, T3, T4, T5]]
  ): RemoteTuple5.Syntax[T1, T2, T3, T4, T5] = new RemoteTuple5.Syntax(remote)
  implicit def remoteTuple6Syntax[T1, T2, T3, T4, T5, T6](
    remote: Remote[Tuple6[T1, T2, T3, T4, T5, T6]]
  ): RemoteTuple6.Syntax[T1, T2, T3, T4, T5, T6] = new RemoteTuple6.Syntax(remote)
  implicit def remoteTuple7Syntax[T1, T2, T3, T4, T5, T6, T7](
    remote: Remote[Tuple7[T1, T2, T3, T4, T5, T6, T7]]
  ): RemoteTuple7.Syntax[T1, T2, T3, T4, T5, T6, T7] = new RemoteTuple7.Syntax(remote)
  implicit def remoteTuple8Syntax[T1, T2, T3, T4, T5, T6, T7, T8](
    remote: Remote[Tuple8[T1, T2, T3, T4, T5, T6, T7, T8]]
  ): RemoteTuple8.Syntax[T1, T2, T3, T4, T5, T6, T7, T8] = new RemoteTuple8.Syntax(remote)
  implicit def remoteTuple9Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9](
    remote: Remote[Tuple9[T1, T2, T3, T4, T5, T6, T7, T8, T9]]
  ): RemoteTuple9.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9] = new RemoteTuple9.Syntax(remote)
  implicit def remoteTuple10Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](
    remote: Remote[Tuple10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10]]
  ): RemoteTuple10.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10] = new RemoteTuple10.Syntax(remote)
  implicit def remoteTuple11Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](
    remote: Remote[Tuple11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11]]
  ): RemoteTuple11.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11] = new RemoteTuple11.Syntax(remote)
  implicit def remoteTuple12Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](
    remote: Remote[Tuple12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12]]
  ): RemoteTuple12.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12] = new RemoteTuple12.Syntax(remote)
  implicit def remoteTuple13Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](
    remote: Remote[Tuple13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13]]
  ): RemoteTuple13.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13] = new RemoteTuple13.Syntax(remote)
  implicit def remoteTuple14Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](
    remote: Remote[Tuple14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14]]
  ): RemoteTuple14.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14] =
    new RemoteTuple14.Syntax(remote)
  implicit def remoteTuple15Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](
    remote: Remote[Tuple15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15]]
  ): RemoteTuple15.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15] =
    new RemoteTuple15.Syntax(remote)
  implicit def remoteTuple16Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](
    remote: Remote[Tuple16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16]]
  ): RemoteTuple16.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16] =
    new RemoteTuple16.Syntax(remote)
  implicit def remoteTuple17Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](
    remote: Remote[Tuple17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17]]
  ): RemoteTuple17.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17] =
    new RemoteTuple17.Syntax(remote)
  implicit def remoteTuple18Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](
    remote: Remote[Tuple18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18]]
  ): RemoteTuple18.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18] =
    new RemoteTuple18.Syntax(remote)
  implicit def remoteTuple19Syntax[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19
  ](
    remote: Remote[Tuple19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19]]
  ): RemoteTuple19.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19] =
    new RemoteTuple19.Syntax(remote)
  implicit def remoteTuple20Syntax[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20
  ](
    remote: Remote[Tuple20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20]]
  ): RemoteTuple20.Syntax[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20] =
    new RemoteTuple20.Syntax(remote)
  implicit def remoteTuple21Syntax[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21
  ](
    remote: Remote[
      Tuple21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21]
    ]
  ): RemoteTuple21.Syntax[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21
  ] = new RemoteTuple21.Syntax(remote)
  implicit def remoteTuple22Syntax[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21,
    T22
  ](
    remote: Remote[
      Tuple22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22]
    ]
  ): RemoteTuple22.Syntax[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21,
    T22
  ] = new RemoteTuple22.Syntax(remote)

  implicit def RemoteList[A](remote: Remote[List[A]]): RemoteListSyntax[A] =
    new RemoteListSyntax[A](remote, trackingEnabled = false)
  implicit def RemoteListCompanion(list: List.type): RemoteListCompanionSyntax = new RemoteListCompanionSyntax(list)

  implicit def RemoteSet[A](remote: Remote[Set[A]]): RemoteSetSyntax[A] = new RemoteSetSyntax[A](remote)

  implicit def RemoteListChar(remote: Remote[List[Char]]): RemoteListCharSyntax = new RemoteListCharSyntax(remote)

  implicit def RemoteOption[A](remote: Remote[Option[A]]): RemoteOptionSyntax[A] = new RemoteOptionSyntax[A](remote)

  implicit def RemoteChar(remote: Remote[Char]): RemoteCharSyntax = new RemoteCharSyntax(remote)

  implicit def RemoteString(remote: Remote[String]): RemoteStringSyntax =
    new RemoteStringSyntax(remote, trackingEnabled = false)

  implicit def remoteStringInterpolator(ctx: StringContext): RemoteStringInterpolator = new RemoteStringInterpolator(
    ctx
  )

  implicit def RemoteExecutingFlow[E, A](remote: Remote[ExecutingFlow[E, A]]): RemoteExecutingFlowSyntax[E, A] =
    new RemoteExecutingFlowSyntax[E, A](remote)

  implicit def RemoteNumeric[A](remote: Remote[A]): RemoteNumericSyntax[A] = new RemoteNumericSyntax[A](remote)

  implicit def RemoteRelational[A](remote: Remote[A]): RemoteRelationalSyntax[A] = new RemoteRelationalSyntax[A](remote)

  implicit def RemoteFractional[A](remote: Remote[A]): RemoteFractionalSyntax[A] = new RemoteFractionalSyntax[A](remote)

  implicit class ZFlowSyntax[R, E, A](flow: ZFlow[R, E, A]) {
    def toRemote: Remote.Flow[R, E, A] = Remote.Flow(flow)
  }
}
