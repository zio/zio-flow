package zio.flow.utils

import zio._
import zio.flow.internal.{DurableLog, KeyValueStore, PersistentExecutor, ZFlowExecutor}
import zio.flow.serialization.{Deserializer, Serializer}
import zio.flow.utils.MockHelpers._

object MockExecutors {
//
//  val mockInMemoryTestClock: ZIO[Clock with Console, Nothing, InMemory[String, Clock with Console]] = ZIO
//    .environment[Clock with Console]
//    .flatMap(testClock =>
//      Ref
//        .make[Map[String, Ref[InMemory.State]]](Map.empty)
//        .map(ref =>
//          InMemory[String, Clock with Console](
//            testClock,
//            ExecutionEnvironment(Serializer.protobuf, Deserializer.protobuf),
//            mockOpExec,
//            ref
//          )
//        )
//    )
//
//  val mockInMemoryLiveClock: ZIO[Any, Nothing, InMemory[String, Clock with Console]] =
//    Ref
//      .make[Map[String, Ref[InMemory.State]]](Map.empty)
//      .map(ref =>
//        InMemory(
//          ZEnvironment(Clock.ClockLive) ++ ZEnvironment(Console.ConsoleLive),
//          ExecutionEnvironment(Serializer.protobuf, Deserializer.protobuf),
//          mockOpExec,
//          ref
//        )
//      )

  val mockPersistentTestClock: ZIO[Scope with DurableLog with KeyValueStore, Nothing, ZFlowExecutor] = {
    PersistentExecutor
      .make(
        mockOpExec,
        Serializer.json,
        Deserializer.json
      )
      .build
      .map(_.get[ZFlowExecutor])

  }

}
