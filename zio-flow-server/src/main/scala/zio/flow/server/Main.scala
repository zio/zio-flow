package zio.flow.server

import zhttp.service.Server
import zio._
import zio.flow.OperationExecutor
import zio.flow.internal.{DurableLog, IndexedStore, KeyValueStore, PersistentExecutor}
import zio.flow.serialization.{Deserializer, Serializer}

object Main extends ZIOAppDefault {
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = (for {
    endpoint <- ZFlowEndpoint.endpoint
    _        <- Server.start(8090, endpoint)
  } yield ())
    .provide(
      KeyValueStore.inMemory,
      FlowTemplates.layer,
      ZFlowEndpoint.layer,
      DurableLog.live,
      IndexedStore.inMemory,
      OperationExecutor.live,
      ZLayer.service[OperationExecutor[Any]].flatMap { executor =>
        PersistentExecutor.make(
          executor.get,
          Serializer.json,
          Deserializer.json
        )
      }
    )
}
