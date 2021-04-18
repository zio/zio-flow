package zio

import java.time._

import scala.language.implicitConversions

package object flow {
  type ActivityError

  type Variable[A]

  type RemoteDuration    = Remote[Duration]
  type RemoteInstant     = Remote[Instant]
  type RemoteVariable[A] = Remote[Variable[A]]

  implicit def RemoteVariable[A](remote: Remote[Variable[A]]): RemoteVariableSyntax[A] = new RemoteVariableSyntax(
    remote
  )
  implicit def RemoteInstant(remote: Remote[Instant]): RemoteInstantSyntax             = new RemoteInstantSyntax(
    remote
  )
  implicit def RemoteDuration(remote: Remote[Duration]): RemoteDurationSyntax          = new RemoteDurationSyntax(
    remote
  )
  implicit def RemoteBoolean(remote: Remote[Boolean]): RemoteBooleanSyntax             = new RemoteBooleanSyntax(remote)

  implicit def RemoteEither[A, B](remote: Remote[Either[A, B]]): RemoteEitherSyntax[A, B]             = new RemoteEitherSyntax(
    remote
  )
  implicit def RemoteTuple2[A, B](remote: Remote[(A, B)]): RemoteTuple2Syntax[A, B]                   = new RemoteTuple2Syntax(remote)
  implicit def RemoteTuple3[A, B, C](remote: Remote[(A, B, C)]): RemoteTuple3Syntax[A, B, C]          = new RemoteTuple3Syntax(
    remote
  )
  implicit def RemoteTuple4[A, B, C, D](remote: Remote[(A, B, C, D)]): RemoteTuple4Syntax[A, B, C, D] =
    new RemoteTuple4Syntax(remote)

  implicit def RemoteList[A](remote: Remote[List[A]]): RemoteListSyntax[A] = new RemoteListSyntax[A](remote)

  implicit def RemoteOption[A](remote: Remote[Option[A]]): RemoteOptionSyntax[A] = new RemoteOptionSyntax[A](remote)
}
