package zio.flow.utils

import zio.clock.Clock
import zio.console.Console
import zio.flow.OperationExecutor
import zio.flow.internal.{DurableLog, PersistentExecutor}
import zio.flow.internal.ZFlowExecutor.InMemory
import zio.flow.utils.MockHelpers._
import zio.{Has, Ref, ZIO}

object MockExecutors {

  val mockInMemoryTestClock: ZIO[Clock with Console, Nothing, InMemory[String, Clock with Console]] = ZIO
    .environment[Clock with Console]
    .flatMap(testClock =>
      Ref
        .make[Map[String, Ref[InMemory.State]]](Map.empty)
        .map(ref => InMemory[String, Clock with Console](testClock, mockOpExec, ref))
    )

  val mockInMemoryLiveClock: ZIO[Any, Nothing, InMemory[String, Has[Clock.Service] with Has[Console.Service]]] =
    Ref
      .make[Map[String, Ref[InMemory.State]]](Map.empty)
      .map(ref => InMemory(Has(zio.clock.Clock.Service.live) ++ Has(zio.console.Console.Service.live), mockOpExec, ref))

  val mockPersistentLiveClock: ZIO[Has[DurableLog], Nothing, PersistentExecutor] =
    for {
      durableLog <- ZIO.service[DurableLog]
      ref        <- Ref.make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
    } yield PersistentExecutor(
      Clock.Service.live,
      durableLog,
      doesNothingKVStore,
      mockOpExec.asInstanceOf[OperationExecutor[Any]],
      ref
    )

  val mockPersistentTestClock =
    for {
      durableLog <- ZIO.service[DurableLog]
      clock      <- ZIO.service[Clock.Service]
      ref        <- Ref.make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
    } yield PersistentExecutor(
      clock,
      durableLog,
      doesNothingKVStore,
      mockOpExec.asInstanceOf[OperationExecutor[Any]],
      ref
    )
}
