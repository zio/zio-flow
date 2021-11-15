package zio

import scala.language.implicitConversions
package object flow {
  type ActivityError = Throwable

  type Variable[A]
  type ExecutingFlow[+E, +A]
}
