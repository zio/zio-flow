package zio.flow.server

import zhttp.service.Server
import zio._
import zio.flow.Configuration
import zio.flow.runtime.internal.{DefaultOperationExecutor, PersistentExecutor}
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore}
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
      DefaultOperationExecutor.layer,
      ZLayer.succeed(Serializer.json),
      ZLayer.succeed(Deserializer.json),
      PersistentExecutor.make()
    )
}
