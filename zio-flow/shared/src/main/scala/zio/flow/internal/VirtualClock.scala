package zio.flow.internal

import zio.{Ref, UIO, ZIO}

trait VirtualClock {
  def current: UIO[Timestamp]
  def advance(toLargerThan: Timestamp): UIO[Unit]
}

object VirtualClock {
  def make(initial: Timestamp): ZIO[Any, Nothing, VirtualClock] =
    Ref.make(initial).map { timestamp =>
      new VirtualClock {
        def current: UIO[Timestamp]                     = timestamp.get
        def advance(toLargerThan: Timestamp): UIO[Unit] = timestamp.update(current => current.max(toLargerThan).next)
      }
    }

  def current: ZIO[VirtualClock, Nothing, Timestamp] =
    ZIO.serviceWithZIO(_.current)

  def advance(toLargerThan: Timestamp): ZIO[VirtualClock, Nothing, Unit] =
    ZIO.serviceWithZIO(_.advance(toLargerThan))
}
