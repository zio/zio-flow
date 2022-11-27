package zio.flow.runtime.operation.http

import zio.{Config, Duration}

/** Specifies how to retry an operation */
sealed trait Repetition

object Repetition {

  /** Wait a fixed interval before retrying */
  final case class Fixed(interval: Duration) extends Repetition

  /**
   * Wait a time interval before retrying that grows exponentially every time
   */
  final case class Exponential(base: Duration, factor: Double, max: Duration) extends Repetition

  val config: Config[Repetition] =
    Config.duration("fixed").map(Fixed) orElse
      (
        Config.duration("base") ++
          Config.double("factor") ++
          Config.duration("max")
      ).nested("exponential").map { case (base, factor, max) => Exponential(base, factor, max) }
}
