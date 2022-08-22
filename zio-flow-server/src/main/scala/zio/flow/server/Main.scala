package zio.flow.server

import zhttp.service.Server
import zio._
import zio.flow.ExecutionEnvironment
import zio.flow.internal.KeyValueStore
import zio.flow.serialization.{Deserializer, Serializer}

object Main extends ZIOAppDefault {
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = (for {
    endpoint <- ZFlowEndpoint.endpoint
    _        <- Server.start(8090, endpoint)
  } yield ())
    .provide(
      ZLayer.succeed(ExecutionEnvironment(Serializer.json, Deserializer.json)),
      KeyValueStore.inMemory,
      ZFlowService.layer,
      ZFlowEndpoint.layer
    )
}
