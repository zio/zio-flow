package zio.flow.server

import zhttp.service.Server
import zio._
import zio.flow.{Configuration, OperationExecutor}
import zio.flow.internal.{DefaultOperationExecutor, DurableLog, IndexedStore, KeyValueStore, PersistentExecutor}
import zio.flow.serialization.{Deserializer, Serializer}

object Main extends ZIOAppDefault {
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = (for {
    endpoint <- ZFlowEndpoint.endpoint
    _        <- Server.start(8090, endpoint)
  } yield ())
    .provide(
      Configuration.inMemory,
      KeyValueStore.inMemory,
      FlowTemplates.layer,
      ZFlowEndpoint.layer,
      DurableLog.layer,
      IndexedStore.inMemory,
      DefaultOperationExecutor.live,
      ZLayer.service[OperationExecutor].flatMap { executor =>
        PersistentExecutor.make(
          executor.get,
          Serializer.json,
          Deserializer.json
        )
      }
    )
}
