package zio.flow.runtime.internal

import zio.ZIO
import zio.flow.runtime.internal.RetryLogic.{optionallyJittered, repetitionToSchedule, retryLimitToSchedule}
import zio.flow.runtime.operation.http.RetryPolicy
import zio.stm.TRef

trait CircuitBreaker {
  def isOpen: ZIO[Any, Nothing, Boolean]
  def onSuccess(): ZIO[Any, Nothing, Unit]
  def onFailure(): ZIO[Any, Nothing, Unit]
}

object CircuitBreaker {
  def make(resetPolicy: RetryPolicy): ZIO[Any, Nothing, CircuitBreaker] =
    for {
      state <- TRef.make[State](State.Closed).commit
      schedule =
        optionallyJittered(resetPolicy.jitter, repetitionToSchedule(resetPolicy.repeatWith)) &&
          retryLimitToSchedule(resetPolicy.failAfter)
      driver <- schedule.driver
    } yield new CircuitBreaker {
      override def isOpen: ZIO[Any, Nothing, Boolean] =
        state.modify {
          case State.Open =>
            (true, State.Open)
          case State.HalfOpen(false) =>
            (false, State.HalfOpen(true))
          case State.HalfOpen(true) =>
            (true, State.HalfOpen(true))
          case State.Closed =>
            (false, State.Closed)
        }.commit

      override def onSuccess(): ZIO[Any, Nothing, Unit] =
        state.update {
          case State.Open        => State.Open
          case State.HalfOpen(_) => State.Closed
          case State.Closed      => State.Closed
        }.commit

      override def onFailure(): ZIO[Any, Nothing, Unit] =
        state.modify {
          case State.Closed =>
            (open(), State.Open)
          case State.HalfOpen(_) =>
            (open(), State.Open)
          case State.Open =>
            (ZIO.unit, State.Open)
        }.commit.flatten

      private def open(): ZIO[Any, Nothing, Unit] =
        (driver.next(()) *> halfOpen())
          .catchAll(_ => driver.reset *> driver.next(()) *> halfOpen())
          .fork
          .unit

      private def halfOpen(): ZIO[Any, Nothing, Unit] =
        state.update {
          case State.Closed          => State.Closed
          case State.HalfOpen(tried) => State.HalfOpen(tried)
          case State.Open            => State.HalfOpen(tried = false)
        }.commit
    }

  def disabled: ZIO[Any, Nothing, CircuitBreaker] =
    ZIO.succeed(new CircuitBreaker {
      override def isOpen: ZIO[Any, Nothing, Boolean] =
        ZIO.succeed(false)

      override def onSuccess(): ZIO[Any, Nothing, Unit] =
        ZIO.unit

      override def onFailure(): ZIO[Any, Nothing, Unit] =
        ZIO.unit
    })

  private sealed trait State
  private object State {
    case object Closed                        extends State
    case object Open                          extends State
    final case class HalfOpen(tried: Boolean) extends State
  }
}
