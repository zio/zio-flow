package zio

import java.time._

package object flow {
  type ActivityError

  type Variable[A]

  type RemoteDuration    = Remote[Duration]
  type RemoteInstant     = Remote[Instant]
  type RemoteVariable[A] = Remote[Variable[A]]

  implicit def RemoteVariable[A](remote: Remote[Variable[A]]): RemoteVariableSyntax[A] = new RemoteVariableSyntax(
    remote
  )
}
