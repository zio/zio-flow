package zio.flow.server

import zhttp.service.Server
import zio._
import zio.flow.internal.{DurableLog, IndexedStore, KeyValueStore, PersistentExecutor}
import zio.flow.serialization.{Deserializer, Serializer}
import zio.flow.{ActivityError, ExecutionEnvironment, Operation, OperationExecutor}

object Main extends ZIOAppDefault {
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = (for {
    endpoint <- ZFlowEndpoint.endpoint
    _        <- Server.start(8090, endpoint)
  } yield ())
    .provide(
      ZLayer.succeed(ExecutionEnvironment(Serializer.json, Deserializer.json)),
      KeyValueStore.inMemory,
      FlowTemplates.layer,
      ZFlowEndpoint.layer,
      DurableLog.live,
      IndexedStore.inMemory,
      PersistentExecutor.make(
        new OperationExecutor[Any] {
          def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Any, ActivityError, A] = ???
        },
        Serializer.json,
        Deserializer.json
      )
    )
}
