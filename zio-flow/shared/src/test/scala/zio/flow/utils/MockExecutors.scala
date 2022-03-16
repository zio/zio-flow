//package zio.flow.utils
//
//import zio._
//import zio.flow.{ExecutionEnvironment, OperationExecutor}
//import zio.flow.internal.{DurableLog, KeyValueStore, PersistentExecutor, ZFlowExecutor}
//import zio.flow.internal.ZFlowExecutor.InMemory
//import zio.flow.serialization.{Deserializer, Serializer}
//import zio.flow.utils.MockHelpers._
//
//object MockExecutors {
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
//
//  val mockPersistentLiveClock: ZIO[DurableLog, Nothing, PersistentExecutor] =
//    for {
//      durableLog <- ZIO.service[DurableLog]
//      ref        <- Ref.make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
//    } yield PersistentExecutor(
//      Clock.ClockLive,
//      ExecutionEnvironment(Serializer.protobuf, Deserializer.protobuf),
//      durableLog,
//      doesNothingKVStore,
//      mockOpExec.asInstanceOf[OperationExecutor[Any]],
//      ref
//    )
//
//  val mockPersistentTestClock
//    : ZManaged[Console with Clock with DurableLog with KeyValueStore, Nothing, ZFlowExecutor[String]] = {
//    ZManaged.environment[Console with Clock].flatMap { env =>
//      PersistentExecutor
//        .make(
//          mockOpExec.provideEnvironment(env),
//          Serializer.json,
//          Deserializer.json
//        )
//        .build
//        .map(_.get[ZFlowExecutor[String]])
//    }
//  }
//}
