package zio.flow.runtime.operation.http

import zio.Duration

sealed trait Repetition

object Repetition {
  final case class Fixed(interval: Duration)                                  extends Repetition
  final case class Exponential(base: Duration, factor: Double, max: Duration) extends Repetition
}
