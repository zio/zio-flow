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

  implicit def RemoteInstant(remote: Remote[Instant]): RemoteInstantSyntax = new RemoteInstantSyntax(
    remote
  )

  implicit def RemoteDuration(remote: Remote[Duration]): RemoteDurationSyntax = new RemoteDurationSyntax(
    remote
  )
  implicit def RemoteBoolean(remote: Remote[Boolean]): RemoteBooleanSyntax    = new RemoteBooleanSyntax(remote)

  implicit def RemoteEither[A, B](remote: Remote[Either[A, B]]): RemoteEitherSyntax[A, B] = new RemoteEitherSyntax(
    remote
  )

}
