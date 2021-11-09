package zio

package object flow {
  type ActivityError = Throwable

  type Variable[A]
  type ExecutingFlow[+E, +A]
}
